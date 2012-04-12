package aQute.lib.deployer.obr;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osgi.service.bindex.BundleIndexer;
import org.osgi.service.coordinator.Coordination;
import org.osgi.service.coordinator.Coordinator;
import org.osgi.service.coordinator.Participant;

import aQute.bnd.service.Refreshable;
import aQute.bnd.service.RepositoryListenerPlugin;
import aQute.lib.io.IO;
import aQute.lib.osgi.Jar;
import aQute.libg.filerepo.FileRepo;
import aQute.libg.tuple.Pair;
import aQute.libg.version.Version;
import aQute.libg.version.VersionRange;

public class LocalOBR extends OBR implements Refreshable, Participant {
	
	public static final String PROP_LOCAL_DIR = "local";
	public static final String PROP_READONLY = "readonly";

	private static final VersionRange RANGE_ANY = new VersionRange(Version.LOWEST.toString());

	private FileRepo storageRepo;
	private boolean readOnly;
	private File storageDir;
	private File localIndex;
	
	private List<URL> indexUrls;

	// @GuardedBy("newFilesInCoordination")
	private final List<Pair<Jar, File>> newFilesInCoordination = new LinkedList<Pair<Jar,File>>();

	@Override
	public void setProperties(Map<String, String> map) {
		super.setProperties(map);
		
		// Load essential properties
		String localDirPath = map.get(PROP_LOCAL_DIR);
		if (localDirPath == null)
			throw new IllegalArgumentException(String.format("Attribute '%s' must be set on LocalOBR plugin.", PROP_LOCAL_DIR));
		storageDir = new File(localDirPath);
		if (!storageDir.isDirectory())
			throw new IllegalArgumentException(String.format("Local path '%s' does not exist or is not a directory.", localDirPath));
		readOnly = "true".equalsIgnoreCase(map.get(PROP_READONLY));
		
		// Configure the storage repository
		storageRepo = new FileRepo(storageDir);
		
		// Set the local index and cache directory locations
		localIndex = new File(storageDir, REPOSITORY_FILE_NAME);
		if (localIndex.exists() && !localIndex.isFile())
			throw new IllegalArgumentException(String.format("Cannot build local repository index: '%s' already exists but is not a plain file.", localIndex.getAbsolutePath()));
		cacheDir = new File(storageDir, ".obrcache");
		if (cacheDir.exists() && !cacheDir.isDirectory())
			throw new IllegalArgumentException(String.format("Cannot create repository cache: '%s' already exists but is not directory.", cacheDir.getAbsolutePath()));
	}
	
	@Override
	protected void initialiseIndexes() throws Exception {
		if (!localIndex.exists()) {
			regenerateIndex();
		}
		try {
			Collection<URL> remotes = super.getOBRIndexes();
			indexUrls = new ArrayList<URL>(remotes.size() + 1);
			indexUrls.add(localIndex.toURI().toURL());
			indexUrls.addAll(remotes);
		} catch (IOException e) {
			throw new IllegalArgumentException("Error initialising local index URL", e);
		}
	}
	
	private synchronized void regenerateIndex() throws Exception {
		BundleIndexer indexer = registry.getPlugin(BundleIndexer.class);
		if (indexer == null)
			throw new IllegalStateException("Cannot index repository: no Bundle Indexer service or plugin found.");
		
		Set<File> allFiles = new HashSet<File>();
		gatherFiles(allFiles);
		
		FileOutputStream out = new FileOutputStream(localIndex);
		try {
			if (!allFiles.isEmpty()) {
				Map<String, String> config = new HashMap<String, String>();
				config.put(BundleIndexer.REPOSITORY_NAME, this.getName());
				config.put(BundleIndexer.ROOT_URL, localIndex.getCanonicalFile().toURI().toURL().toString());
				indexer.index(allFiles, out, config);
			} else {
				ByteArrayInputStream emptyRepo = new ByteArrayInputStream("<?xml version='1.0' encoding='UTF-8'?>\n<repository lastmodified='0'/>".getBytes());
				IO.copy(emptyRepo, out);
			}
		} finally {
			out.close();
		}
	}

	private void gatherFiles(Set<File> allFiles) throws Exception {
		List<String> bsns = storageRepo.list(null);
		if (bsns != null) for (String bsn : bsns) {
			File[] files = storageRepo.get(bsn, RANGE_ANY);
			if (files != null) for (File file : files) {
				allFiles.add(file.getCanonicalFile());
			}
		}
	}

	@Override
	public List<URL> getOBRIndexes() {
		return indexUrls;
	}
	
	@Override
	public boolean canWrite() {
		return !readOnly;
	}
	
	private File beginPut(Jar jar) throws Exception {
		String bsn = jar.getBsn();
		if (bsn == null)
			throw new IllegalArgumentException("Jar does not have a Bundle-SymbolicName manifest header");
		
		File dir = new File(storageDir, bsn);
		if (dir.exists() && !dir.isDirectory())
			throw new IllegalArgumentException("Path already exists but is not a directory: " + dir.getAbsolutePath());
		dir.mkdirs();

		Version version = Version.parseVersion(jar.getVersion());
		String fileName = String.format("%s-%d.%d.%d.jar", bsn, version.getMajor(), version.getMinor(), version.getMicro());
		File file = new File(dir, fileName);
		jar.write(file);
		
		synchronized (newFilesInCoordination) {
			newFilesInCoordination.add(new Pair<Jar, File>(jar, file));
		}
		return file;
	}
	
	private synchronized void finishPut() throws Exception {
		reset();
		regenerateIndex();
		List<Pair<Jar,File>> clone = new ArrayList<Pair<Jar, File>>(newFilesInCoordination);
		synchronized (newFilesInCoordination) {
			newFilesInCoordination.clear();
		}
		for (Pair<Jar, File> entry : clone) {
			fireBundleAdded(entry.a, entry.b);
		}
	}
	
	public synchronized void ended(Coordination coordination) throws Exception {
		finishPut();
	}
	
	public void failed(Coordination coordination) throws Exception {
		ArrayList<Pair<Jar,File>> clone;
		synchronized (newFilesInCoordination) {
			clone = new ArrayList<Pair<Jar, File>>(newFilesInCoordination);
			newFilesInCoordination.clear();
		}
		for (Pair<Jar, File> entry : clone) {
			try {
				entry.b.delete();
			} catch (Exception e) {
				reporter.warning("Failed to remove repository entry %s on coordination rollback: %s", entry.b, e);
			}
		}
	}
	
	@Override
	public synchronized File put(Jar jar) throws Exception {
		Coordinator coordinator = registry.getPlugin(Coordinator.class);
		File newFile;
		if (coordinator != null && coordinator.addParticipant(this)) {
			newFile = beginPut(jar);
		} else {
			newFile = beginPut(jar);
			finishPut();
		}
		return newFile;
	}

	public boolean refresh() {
		reset();
		return true;
	}

	public File getRoot() {
		return storageDir;
	}
	
	protected void fireBundleAdded(Jar jar, File file) {
		if (registry == null)
			return;
		List<RepositoryListenerPlugin> listeners = registry.getPlugins(RepositoryListenerPlugin.class);
		for (RepositoryListenerPlugin listener : listeners) {
			try {
				listener.bundleAdded(this, jar, file);
			} catch (Exception e) {
				if (reporter != null)
					reporter.warning("Repository listener threw an unexpected exception: %s", e);
			}
		}
	}
}