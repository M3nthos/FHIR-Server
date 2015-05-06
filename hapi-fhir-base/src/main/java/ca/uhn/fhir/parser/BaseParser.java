package ca.uhn.fhir.parser;

/*
 * #%L
 * HAPI FHIR - Core Library
 * %%
 * Copyright (C) 2014 - 2015 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.IBase;
import org.hl7.fhir.instance.model.IBaseResource;
import org.hl7.fhir.instance.model.IPrimitiveType;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IDomainResource;
import org.hl7.fhir.instance.model.api.IReference;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeDeclaredChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeChildChoiceDefinition;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.model.api.Bundle;
import ca.uhn.fhir.model.api.BundleEntry;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.base.composite.BaseResourceReferenceDt;
import ca.uhn.fhir.model.primitive.IdDt;

public abstract class BaseParser implements IParser {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseParser.class);
	private ContainedResources myContainedResources;
	private FhirContext myContext;
	private boolean mySuppressNarratives;
	private String myServerBaseUrl;

	public BaseParser(FhirContext theContext) {
		myContext = theContext;
	}

	private void containResourcesForEncoding(ContainedResources theContained, IBaseResource theResource, IBaseResource theTarget) {

		Set<String> allIds = new HashSet<String>();
		Map<String, IBaseResource> existingIdToContainedResource = null;

		if (theTarget instanceof IResource) {
			List<? extends IResource> containedResources = ((IResource) theTarget).getContained().getContainedResources();
			for (IResource next : containedResources) {
				String nextId = next.getId().getValue();
				if (StringUtils.isNotBlank(nextId)) {
					if (!nextId.startsWith("#")) {
						nextId = '#' + nextId;
					}
					allIds.add(nextId);
					if (existingIdToContainedResource == null) {
						existingIdToContainedResource = new HashMap<String, IBaseResource>();
					}
					existingIdToContainedResource.put(nextId, next);
				}
			}
		} else if (theTarget instanceof IDomainResource) {
			List<? extends IAnyResource> containedResources = ((IDomainResource) theTarget).getContained();
			for (IAnyResource next : containedResources) {
				String nextId = next.getId();
				if (StringUtils.isNotBlank(nextId)) {
					allIds.add(nextId);
					if (existingIdToContainedResource == null) {
						existingIdToContainedResource = new HashMap<String, IBaseResource>();
					}
					existingIdToContainedResource.put(nextId, next);
				}
			}
		} else {
			// no resources to contain
		}

		{
			List<BaseResourceReferenceDt> allElements = myContext.newTerser().getAllPopulatedChildElementsOfType(theResource, BaseResourceReferenceDt.class);
			for (BaseResourceReferenceDt next : allElements) {
				IResource resource = next.getResource();
				if (resource != null) {
					if (resource.getId().isEmpty() || resource.getId().isLocal()) {
						theContained.addContained(resource);
					} else {
						continue;
					}

					containResourcesForEncoding(theContained, resource, theTarget);
				} else if (next.getReference().isLocal()) {
					if (existingIdToContainedResource != null) {
						IBaseResource potentialTarget = existingIdToContainedResource.remove(next.getReference().getValue());
						if (potentialTarget != null) {
							theContained.addContained(next.getReference(), potentialTarget);
							containResourcesForEncoding(theContained, potentialTarget, theTarget);
						}
					}
				}
			}
		}

		{
			List<IReference> allElements = myContext.newTerser().getAllPopulatedChildElementsOfType(theResource, IReference.class);
			for (IReference next : allElements) {
				IAnyResource resource = next.getResource();
				if (resource != null) {
					if (resource.getIdElement().isEmpty() || resource.getId().startsWith("#")) {
						theContained.addContained(resource);
					} else {
						continue;
					}

					containResourcesForEncoding(theContained, resource, theTarget);
				} else if (next.getReference() != null && next.getReference().startsWith("#")) {
					if (existingIdToContainedResource != null) {
						IBaseResource potentialTarget = existingIdToContainedResource.remove(next.getReference());
						if (potentialTarget != null) {
							theContained.addContained(potentialTarget);
							containResourcesForEncoding(theContained, potentialTarget, theTarget);
						}
					}
				}
			}
		}

	}

	protected void containResourcesForEncoding(IBaseResource theResource) {
		ContainedResources contained = new ContainedResources();
		containResourcesForEncoding(contained, theResource, theResource);
		myContainedResources = contained;
	}

	protected String determineReferenceText(BaseResourceReferenceDt theRef) {
		IdDt ref = theRef.getReference();
		if (isBlank(ref.getIdPart())) {
			String reference = ref.getValue();
			if (theRef.getResource() != null) {
				IdDt containedId = getContainedResources().getResourceId(theRef.getResource());
				if (containedId != null && !containedId.isEmpty()) {
					if (containedId.isLocal()) {
						reference = containedId.getValue();
					} else {
						reference = "#" + containedId.getValue();
					}
				} else if (theRef.getResource().getId() != null && theRef.getResource().getId().hasIdPart()) {
					reference = theRef.getResource().getId().getValue();
				}
			}
			return reference;
		} else {
			if (isNotBlank(myServerBaseUrl) && StringUtils.equals(myServerBaseUrl, ref.getBaseUrl())) {
				String reference = ref.toUnqualifiedVersionless().getValue();
				return reference;
			} else {
				String reference = ref.toVersionless().getValue();
				return reference;
			}
		}
	}

	@Override
	public IParser setServerBaseUrl(String theUrl) {
		myServerBaseUrl = isNotBlank(theUrl) ? theUrl : null;
		return this;
	}

	protected String determineResourceBaseUrl(String bundleBaseUrl, BundleEntry theEntry) {
		IResource resource = theEntry.getResource();
		if (resource == null) {
			return null;
		}

		String resourceBaseUrl = null;
		if (resource.getId() != null && resource.getId().hasBaseUrl()) {
			if (!resource.getId().getBaseUrl().equals(bundleBaseUrl)) {
				resourceBaseUrl = resource.getId().getBaseUrl();
			}
		}
		return resourceBaseUrl;
	}

	protected abstract <T extends IBaseResource> T doParseResource(Class<T> theResourceType, Reader theReader) throws DataFormatException;

	@Override
	public String encodeBundleToString(Bundle theBundle) throws DataFormatException {
		if (theBundle == null) {
			throw new NullPointerException("Bundle can not be null");
		}
		StringWriter stringWriter = new StringWriter();
		try {
			encodeBundleToWriter(theBundle, stringWriter);
		} catch (IOException e) {
			throw new Error("Encountered IOException during write to string - This should not happen!");
		}

		return stringWriter.toString();
	}

	@Override
	public String encodeResourceToString(IBaseResource theResource) throws DataFormatException {
		Writer stringWriter = new StringWriter();
		try {
			encodeResourceToWriter(theResource, stringWriter);
		} catch (IOException e) {
			throw new Error("Encountered IOException during write to string - This should not happen!");
		}
		return stringWriter.toString();
	}

	@Override
	public String encodeTagListToString(TagList theTagList) {
		Writer stringWriter = new StringWriter();
		try {
			encodeTagListToWriter(theTagList, stringWriter);
		} catch (IOException e) {
			throw new Error("Encountered IOException during write to string - This should not happen!");
		}
		return stringWriter.toString();
	}

	protected String fixContainedResourceId(String theValue) {
		if (StringUtils.isNotBlank(theValue) && theValue.charAt(0) == '#') {
			return theValue.substring(1);
		}
		return theValue;
	}

	ContainedResources getContainedResources() {
		return myContainedResources;
	}

	/**
	 * If set to <code>true</code> (default is <code>false</code>), narratives will not be included in the encoded values.
	 */
	public boolean getSuppressNarratives() {
		return mySuppressNarratives;
	}

	@Override
	public Bundle parseBundle(Reader theReader) {
		return parseBundle(null, theReader);
	}

	@Override
	public Bundle parseBundle(String theXml) throws ConfigurationException, DataFormatException {
		StringReader reader = new StringReader(theXml);
		return parseBundle(reader);
	}

	public <T extends IBaseResource> T parseResource(Class<T> theResourceType, Reader theReader) throws DataFormatException {
		T retVal = doParseResource(theResourceType, theReader);

		RuntimeResourceDefinition def = myContext.getResourceDefinition(retVal);
		if ("Bundle".equals(def.getName())) {
			List<IBase> base = def.getChildByName("base").getAccessor().getValues(retVal);
			if (base != null && base.size() > 0) {
				IPrimitiveType<?> baseType = (IPrimitiveType<?>) base.get(0);
				IResource res = ((IResource) retVal);
				res.setId(new IdDt(baseType.getValueAsString(), def.getName(), res.getId().getIdPart(), res.getId().getVersionIdPart()));
			}

			BaseRuntimeChildDefinition entryChild = def.getChildByName("entry");
			BaseRuntimeElementCompositeDefinition<?> entryDef = (BaseRuntimeElementCompositeDefinition<?>) entryChild.getChildByName("entry");
			List<IBase> entries = entryChild.getAccessor().getValues(retVal);
			if (entries != null) {
				for (IBase nextEntry : entries) {
					List<IBase> entryBase = entryDef.getChildByName("base").getAccessor().getValues(nextEntry);

					if (entryBase == null || entryBase.isEmpty()) {
						entryBase = base;
					}

					if (entryBase != null && entryBase.size() > 0) {
						IPrimitiveType<?> baseType = (IPrimitiveType<?>) entryBase.get(0);

						List<IBase> entryResources = entryDef.getChildByName("resource").getAccessor().getValues(nextEntry);
						if (entryResources != null && entryResources.size() > 0) {
							IResource res = (IResource) entryResources.get(0);
							RuntimeResourceDefinition resDef = myContext.getResourceDefinition(res);
							String versionIdPart = res.getId().getVersionIdPart();
							if (isBlank(versionIdPart)) {
								versionIdPart = ResourceMetadataKeyEnum.VERSION.get(res);
							}

							res.setId(new IdDt(baseType.getValueAsString(), resDef.getName(), res.getId().getIdPart(), versionIdPart));
						}

					}

				}
			}

		}

		return retVal;
	}

	@SuppressWarnings("cast")
	@Override
	public <T extends IBaseResource> T parseResource(Class<T> theResourceType, String theMessageString) {
		StringReader reader = new StringReader(theMessageString);
		return (T) parseResource(theResourceType, reader);
	}

	@Override
	public IResource parseResource(Reader theReader) throws ConfigurationException, DataFormatException {
		return parseResource(null, theReader);
	}

	@Override
	public IResource parseResource(String theMessageString) throws ConfigurationException, DataFormatException {
		return parseResource(null, theMessageString);
	}

	@Override
	public TagList parseTagList(String theString) {
		return parseTagList(new StringReader(theString));
	}

	@Override
	public IParser setSuppressNarratives(boolean theSuppressNarratives) {
		mySuppressNarratives = theSuppressNarratives;
		return this;
	}

	protected void throwExceptionForUnknownChildType(BaseRuntimeChildDefinition nextChild, Class<? extends IBase> theType) {
		if (nextChild instanceof BaseRuntimeDeclaredChildDefinition) {
			StringBuilder b = new StringBuilder();
			b.append(((BaseRuntimeDeclaredChildDefinition) nextChild).getElementName());
			b.append(" has type ");
			b.append(theType.getName());
			b.append(" but this is not a valid type for this element");
			if (nextChild instanceof RuntimeChildChoiceDefinition) {
				RuntimeChildChoiceDefinition choice = (RuntimeChildChoiceDefinition) nextChild;
				b.append(" - Expected one of: " + choice.getValidChildTypes());
			}
			throw new DataFormatException(b.toString());
		}
		throw new DataFormatException(nextChild + " has no child of type " + theType);
	}

	protected static <T> List<T> extractMetadataListNotNull(IResource resource, ResourceMetadataKeyEnum<List<T>> key) {
		List<T> securityLabels = key.get(resource);
		if (securityLabels == null) {
			securityLabels = Collections.emptyList();
		}
		return securityLabels;
	}

	static class ContainedResources {
		private long myNextContainedId = 1;

		private List<IBaseResource> myResources = new ArrayList<IBaseResource>();
		private IdentityHashMap<IBaseResource, IdDt> myResourceToId = new IdentityHashMap<IBaseResource, IdDt>();

		public void addContained(IBaseResource theResource) {
			if (myResourceToId.containsKey(theResource)) {
				return;
			}

			IdDt newId;
			if (theResource instanceof IResource && ((IResource) theResource).getId().isLocal()) {
				newId = ((IResource) theResource).getId();
			} else if (theResource instanceof IAnyResource && ((IAnyResource) theResource).getId() != null && ((IAnyResource) theResource).getId().startsWith("#")) {
				newId = new IdDt(((IAnyResource) theResource).getId());
			} else {
				// TODO: make this configurable between the two below (and something else?)
				// newId = new IdDt(UUID.randomUUID().toString());
				newId = new IdDt(myNextContainedId++);
			}

			myResourceToId.put(theResource, newId);
			myResources.add(theResource);
		}

		public void addContained(IdDt theId, IBaseResource theResource) {
			myResourceToId.put(theResource, theId);
			myResources.add(theResource);
		}

		public List<IBaseResource> getContainedResources() {
			return myResources;
		}

		public IdDt getResourceId(IBaseResource theNext) {
			return myResourceToId.get(theNext);
		}

		public boolean isEmpty() {
			return myResourceToId.isEmpty();
		}

	}

}
