package ca.uhn.fhir.context;

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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.instance.model.IBase;

import ca.uhn.fhir.model.api.annotation.Child;
import ca.uhn.fhir.model.api.annotation.Description;
import ca.uhn.fhir.model.base.composite.BaseContainedDt;

public class RuntimeChildContainedResources extends BaseRuntimeDeclaredChildDefinition {

	private RuntimeElemContainedResources myElem;

	RuntimeChildContainedResources(Field theField, Child theChildAnnotation, Description theDescriptionAnnotation, String theElementName) throws ConfigurationException {
		super(theField, theChildAnnotation, theDescriptionAnnotation, theElementName);
	}

	@Override
	public BaseRuntimeElementDefinition<?> getChildByName(String theName) {
		assert theName.equals(getElementName());
		return myElem;
	}

	@Override
	public BaseRuntimeElementDefinition<?> getChildElementDefinitionByDatatype(Class<? extends IBase> theType) {
		assert BaseContainedDt.class.isAssignableFrom(theType);
		return myElem;
	}

	@Override
	public String getChildNameByDatatype(Class<? extends IBase> theDatatype) {
		assert BaseContainedDt.class.isAssignableFrom(theDatatype);
		return getElementName();
	}

	@Override
	public Set<String> getValidChildNames() {
		return Collections.singleton(getElementName());
	}

	@Override
	void sealAndInitialize(FhirContext theContext, Map<Class<? extends IBase>, BaseRuntimeElementDefinition<?>> theClassToElementDefinitions) {
		Class<?> actualType = theContext.getVersion().getContainedType();
		if (BaseContainedDt.class.isAssignableFrom(actualType)) {
			@SuppressWarnings("unchecked")
			Class<? extends BaseContainedDt> type = (Class<? extends BaseContainedDt>) actualType;
			myElem = new RuntimeElemContainedResources(type);
		} else if (ArrayList.class.isAssignableFrom(actualType)) {
			myElem = null;
		} else {
			throw new ConfigurationException("Fhir Version definition returned invalid contained type: " + actualType);
		}
	}

}
