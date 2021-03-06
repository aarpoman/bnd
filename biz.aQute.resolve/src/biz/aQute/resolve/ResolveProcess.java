package biz.aQute.resolve;

import static org.osgi.framework.namespace.BundleNamespace.BUNDLE_NAMESPACE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.osgi.framework.InvalidSyntaxException;
import org.osgi.resource.Capability;
import org.osgi.resource.Namespace;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.log.LogService;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;
import org.osgi.service.resolver.Resolver;

import aQute.bnd.build.model.BndEditModel;
import aQute.bnd.osgi.Processor;
import aQute.bnd.service.Registry;
import aQute.libg.tuple.Pair;
import biz.aQute.resolve.internal.BndrunResolveContext;

public class ResolveProcess {

	private Map<Resource,List<Wire>>	required;
	private Map<Resource,List<Wire>>	optional;

	private ResolutionException			resolutionException;

	public Map<Resource,List<Wire>> resolveRequired(BndEditModel inputModel, Registry plugins, Resolver resolver,
			Collection<ResolutionCallback> callbacks, LogService log) throws ResolutionException {
		try {
			return resolveRequired(inputModel.getProperties(), plugins, resolver, callbacks, log);
		}
		catch (Exception e) {
			throw new ResolutionException(e);
		}
	}

	public Map<Resource,List<Wire>> resolveRequired(Processor properties, Registry plugins, Resolver resolver,
			Collection<ResolutionCallback> callbacks, LogService log) throws ResolutionException {
		BndrunResolveContext rc = new BndrunResolveContext(properties, plugins, log);
		rc.addCallbacks(callbacks);
		// 1. Resolve initial requirements
		try {
			Map<Resource,List<Wire>> wirings = resolver.resolve(rc);

			// 2. Save initial requirement resolution
			Pair<Resource,List<Wire>> initialRequirement = null;
			for (Map.Entry<Resource,List<Wire>> wiring : wirings.entrySet()) {
				if (rc.getInputResource() == wiring.getKey()) {
					initialRequirement = new Pair<Resource,List<Wire>>(wiring.getKey(), wiring.getValue());
					break;
				}
			}

			// 3. Save the resolved root resources
			final List<Resource> resources = new ArrayList<Resource>();
			for (Resource r : rc.getMandatoryResources()) {
				reqs: for (Requirement req : r.getRequirements(null)) {
					for (Resource found : wirings.keySet()) {
						String filterStr = req.getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE);
						try {
							org.osgi.framework.Filter filter = filterStr != null ? org.osgi.framework.FrameworkUtil
									.createFilter(filterStr) : null;

							for (Capability c : found.getCapabilities(req.getNamespace())) {
								if (filter != null && filter.matches(c.getAttributes())) {
									resources.add(found);
									continue reqs;
								}
							}
						}
						catch (InvalidSyntaxException e) {}
					}
				}
			}

			// 4. Add any 'osgi.wiring.bundle' requirements
			List<Resource> wiredBundles = new ArrayList<Resource>();
			for (Resource resource : resources) {
				addWiredBundle(wirings, resource, wiredBundles);
			}
			for (Resource resource : wiredBundles) {
				if (!resources.contains(resource)) {
					resources.add(resource);
				}
			}

			// 5. Resolve the rest
			BndrunResolveContext rc2 = new BndrunResolveContext(properties, plugins, log) {

				@Override
				public Collection<Resource> getMandatoryResources() {
					return resources;
				}

				@Override
				public boolean isInputResource(Resource resource) {
					for (Resource r : resources) {
						if (GenericResolveContext.resourceIdentityEquals(r, resource)) {
							return true;
						}
					}
					return false;
				}
			};

			rc2.addCallbacks(callbacks);
			wirings = resolver.resolve(rc2);
			if (initialRequirement != null) {
				wirings.put(initialRequirement.getFirst(), initialRequirement.getSecond());
			}

			Map<Resource,List<Wire>> result = invertWirings(wirings);
			removeFrameworkAndInputResources(result, rc2);

			return result;
		}
		catch (ResolutionException re) {
			throw augment(new BndrunResolveContext(properties, plugins, log), re);
		}
	}

	/*
	 * The Felix resolver reports an initial resource as unresolved if one of
	 * its requirements cannot be found, even though it is in the repo. This
	 * method will (try to) analyze what is actually missing. This is not
	 * perfect but should give some more diagnostics in most cases.
	 */
	public static ResolutionException augment(ResolveContext context, ResolutionException re)
			throws ResolutionException {
		try {
			long deadline = System.currentTimeMillis() + 1000;
			Collection<Requirement> unresolved = re.getUnresolvedRequirements();
			Set<Requirement> list = new HashSet<Requirement>(unresolved);
			Set<Resource> resources = new HashSet<Resource>();

			for (Requirement r : unresolved) {
				Requirement find = missing(context, r, resources, deadline);
				if (find != null)
					list.add(find);
			}
			if (!list.isEmpty())
				return new ResolutionException(re.getMessage(), re.getCause(), list);
		}
		catch (TimeoutException toe) {}
		return re;
	}

	/*
	 * Recursively traverse all requirement's resource requirement's
	 */
	private static Requirement missing(ResolveContext context, Requirement rq, Set<Resource> resources, long deadline)
			throws TimeoutException {
		resources.add(rq.getResource());

		if (deadline < System.currentTimeMillis())
			throw new TimeoutException();

		List<Capability> providers = context.findProviders(rq);

		//
		// This requirement cannot be found
		//

		if (providers.isEmpty())
			return rq;

		//
		// We first search breadth first for a capability that
		// satisfies our requirement and its 1st level requirements.
		//

		Set<Resource> candidates = new HashSet<Resource>();

		Requirement missing = null;
		caps: for (Capability cap : providers) {

			for (Requirement sub : cap.getResource().getRequirements(null)) {
				List<Capability> subProviders = context.findProviders(sub);
				if (subProviders.isEmpty()) {
					if (missing == null)
						missing = sub;

					//
					// this cap lacks its 1st level requirement
					// so try next capability
					//

					continue caps;
				}
			}

			//
			// We found a capability for our requirement
			// that matches, of course its resource might fail
			// later

			candidates.add(cap.getResource());
		}

		//
		// If we have no candidates, then we fail ...
		// missing is set then since at least 1 cap must have failed
		// and set missing since #providers > 0. I.e. our requirement
		// found a candidate, but no candidate succeeded to be satisfied.
		// Missing then contains the first missing requirement

		if (candidates.isEmpty()) {
			assert missing != null;
			return missing;
		}

		Requirement initialMissing = missing;
		missing = null;

		//
		// candidates now contains the resources that are potentially
		// able to satisfy our requirements.
		//
		candidates.removeAll(resources);
		resources.addAll(candidates);

		resource: for (Resource resource : candidates) {
			for (Requirement requirement : resource.getRequirements(null)) {
				Requirement r1 = missing(context, requirement, resources, deadline);
				if (r1 != null && missing != null) {
					missing = r1;
					continue resource;
				}
			}

			// A Fully matching resource

			return null;
		}

		//
		// None of the resources was resolvable
		//

		return missing == null ? initialMissing : missing;
	}

	private void addWiredBundle(Map<Resource,List<Wire>> wirings, Resource resource, List<Resource> result) {
		List<Requirement> reqs = resource.getRequirements(BUNDLE_NAMESPACE);
		for (Requirement req : reqs) {
			List<Wire> wrs = wirings.get(resource);
			for (Wire w : wrs) {
				if (w.getRequirement().equals(req)) {
					Resource res = w.getProvider();
					if (res != null) {
						if (!result.contains(res)) {
							result.add(res);
							addWiredBundle(wirings, res, result);
						}
					}
				}
			}
		}
	}

	public Map<Resource,List<Wire>> resolveOptional(BndEditModel inputModel, Set<Resource> requiredResources,
			Registry plugins, Resolver resolver, Collection<ResolutionCallback> callbacks, LogService log)
			throws ResolutionException {
		BndrunResolveContext rc = new BndrunResolveContext(inputModel, plugins, log);
		rc.addCallbacks(callbacks);
		rc.setOptionalRoots(requiredResources);

		Map<Resource,List<Wire>> wirings = resolver.resolve(rc);
		removeFrameworkAndInputResources(wirings, rc);

		// Remove requiredResources
		for (Iterator<Resource> iter = wirings.keySet().iterator(); iter.hasNext();) {
			Resource resource = iter.next();
			if (requiredResources.contains(resource))
				iter.remove();
		}

		return invertWirings(wirings);
	}

	public boolean XXXresolve(BndEditModel inputModel, Registry pluginRegistry, Resolver resolver, LogService log) {
		try {
			// Resolve required resources
			BndrunResolveContext resolveContext = new BndrunResolveContext(inputModel, pluginRegistry, log);
			Map<Resource,List<Wire>> wirings = resolver.resolve(resolveContext);
			required = invertWirings(wirings);
			removeFrameworkAndInputResources(required, resolveContext);

			// Resolve optional resources
			resolveContext = new BndrunResolveContext(inputModel, pluginRegistry, log);
			resolveContext.setOptionalRoots(wirings.keySet());
			Map<Resource,List<Wire>> optionalWirings = resolver.resolve(resolveContext);
			optional = invertWirings(optionalWirings);
			removeFrameworkAndInputResources(optional, resolveContext);

			// Remove required resources from optional resource map
			for (Iterator<Resource> iter = optional.keySet().iterator(); iter.hasNext();) {
				Resource resource = iter.next();
				if (required.containsKey(resource))
					iter.remove();
			}

			return true;
		}
		catch (ResolutionException e) {
			resolutionException = e;
			return false;
		}
	}

	/*
	 * private void processOptionalRequirements(BndrunResolveContext
	 * resolveContext) { optionalReasons = new
	 * HashMap<URI,Map<Capability,Collection<Requirement>>>(); for
	 * (Entry<Requirement,List<Capability>> entry :
	 * resolveContext.getOptionalRequirements().entrySet()) { Requirement req =
	 * entry.getKey(); Resource requirer = req.getResource(); if
	 * (requiredReasons.containsKey(getResourceURI(requirer))) {
	 * List<Capability> caps = entry.getValue(); for (Capability cap : caps) {
	 * Resource providerResource = cap.getResource(); URI resourceUri =
	 * getResourceURI(providerResource); if (requirer != providerResource) { //
	 * && !requiredResources.containsKey(providerResource))
	 * Map<Capability,Collection<Requirement>> resourceReasons =
	 * optionalReasons.get(cap.getResource()); if (resourceReasons == null) {
	 * resourceReasons = new HashMap<Capability,Collection<Requirement>>();
	 * optionalReasons.put(resourceUri, resourceReasons);
	 * urisToResources.put(resourceUri, providerResource); }
	 * Collection<Requirement> capRequirements = resourceReasons.get(cap); if
	 * (capRequirements == null) { capRequirements = new
	 * LinkedList<Requirement>(); resourceReasons.put(cap, capRequirements); }
	 * capRequirements.add(req); } } } } }
	 */

	private static void removeFrameworkAndInputResources(Map<Resource,List<Wire>> resourceMap, GenericResolveContext rc) {
		for (Iterator<Resource> iter = resourceMap.keySet().iterator(); iter.hasNext();) {
			Resource resource = iter.next();
			if (rc.isSystemResource(resource))
				iter.remove();
		}
	}

	/**
	 * Inverts the wiring map from the resolver. Whereas the resolver returns a
	 * map of resources and the list of wirings FROM each resource, we want to
	 * know the list of wirings TO that resource. This is in order to show the
	 * user the reasons for each resource being present in the result.
	 */
	private static Map<Resource,List<Wire>> invertWirings(Map<Resource, ? extends Collection<Wire>> wirings) {
		Map<Resource,List<Wire>> inverted = new HashMap<Resource,List<Wire>>();
		for (Entry<Resource, ? extends Collection<Wire>> entry : wirings.entrySet()) {
			Resource requirer = entry.getKey();
			for (Wire wire : entry.getValue()) {
				Resource provider = wire.getProvider();

				// Filter out self-capabilities, i.e. requirer and provider are
				// same
				if (provider == requirer)
					continue;

				List<Wire> incoming = inverted.get(provider);
				if (incoming == null) {
					incoming = new LinkedList<Wire>();
					inverted.put(provider, incoming);
				}
				incoming.add(wire);
			}
		}
		return inverted;
	}

	public ResolutionException getResolutionException() {
		return resolutionException;
	}

	public Collection<Resource> getRequiredResources() {
		if (required == null)
			return Collections.emptyList();
		return Collections.unmodifiableCollection(required.keySet());
	}

	public Collection<Resource> getOptionalResources() {
		if (optional == null)
			return Collections.emptyList();
		return Collections.unmodifiableCollection(optional.keySet());
	}

	public Collection<Wire> getRequiredReasons(Resource resource) {
		Collection<Wire> wires = required.get(resource);
		if (wires == null)
			wires = Collections.emptyList();
		return wires;
	}

	public Collection<Wire> getOptionalReasons(Resource resource) {
		Collection<Wire> wires = optional.get(resource);
		if (wires == null)
			wires = Collections.emptyList();
		return wires;
	}

}
