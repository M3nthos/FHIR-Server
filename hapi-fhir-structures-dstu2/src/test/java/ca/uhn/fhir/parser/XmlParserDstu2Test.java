package ca.uhn.fhir.parser;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.custommonkey.xmlunit.Diff;
import org.custommonkey.xmlunit.XMLUnit;
import org.junit.BeforeClass;
import org.junit.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.Bundle;
import ca.uhn.fhir.model.api.ExtensionDt;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.api.Tag;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.base.composite.BaseCodingDt;
import ca.uhn.fhir.model.dstu2.composite.CodeableConceptDt;
import ca.uhn.fhir.model.dstu2.composite.CodingDt;
import ca.uhn.fhir.model.dstu2.composite.ContainedDt;
import ca.uhn.fhir.model.dstu2.composite.DurationDt;
import ca.uhn.fhir.model.dstu2.composite.HumanNameDt;
import ca.uhn.fhir.model.dstu2.composite.ResourceReferenceDt;
import ca.uhn.fhir.model.dstu2.resource.AllergyIntolerance;
import ca.uhn.fhir.model.dstu2.resource.Binary;
import ca.uhn.fhir.model.dstu2.resource.Composition;
import ca.uhn.fhir.model.dstu2.resource.Encounter;
import ca.uhn.fhir.model.dstu2.resource.Medication;
import ca.uhn.fhir.model.dstu2.resource.MedicationPrescription;
import ca.uhn.fhir.model.dstu2.resource.Observation;
import ca.uhn.fhir.model.dstu2.resource.Organization;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import ca.uhn.fhir.model.dstu2.valueset.AdministrativeGenderEnum;
import ca.uhn.fhir.model.dstu2.valueset.BundleTypeEnum;
import ca.uhn.fhir.model.dstu2.valueset.IdentifierUseEnum;
import ca.uhn.fhir.model.primitive.DateDt;
import ca.uhn.fhir.model.primitive.DateTimeDt;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.model.primitive.StringDt;

public class XmlParserDstu2Test {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(XmlParserDstu2Test.class);
	private static final FhirContext ourCtx = FhirContext.forDstu2();

	@BeforeClass
	public static void beforeClass() {
		XMLUnit.setIgnoreAttributeOrder(true);
		XMLUnit.setIgnoreComments(true);
		XMLUnit.setIgnoreWhitespace(true);
	}

	/**
	 * See #163
	 */
	@Test
	public void testParseResourceType() {
		IParser xmlParser = ourCtx.newXmlParser().setPrettyPrint(true);

		// Patient
		Patient patient = new Patient();
		String patientId = UUID.randomUUID().toString();
		patient.setId(new IdDt("Patient", patientId));
		patient.addName().addGiven("John").addFamily("Smith");
		patient.setGender(AdministrativeGenderEnum.MALE);
		patient.setBirthDate(new DateDt("1987-04-16"));

		// Bundle
		ca.uhn.fhir.model.dstu2.resource.Bundle bundle = new ca.uhn.fhir.model.dstu2.resource.Bundle();
		bundle.setType(BundleTypeEnum.COLLECTION);
		bundle.addEntry().setResource(patient);

		String bundleText = xmlParser.encodeResourceToString(bundle);
		ourLog.info(bundleText);
		
		ca.uhn.fhir.model.dstu2.resource.Bundle reincarnatedBundle = xmlParser.parseResource (ca.uhn.fhir.model.dstu2.resource.Bundle.class, bundleText);
		Patient reincarnatedPatient = reincarnatedBundle.getAllPopulatedChildElementsOfType(Patient.class).get(0); 
		
		assertEquals("Patient", patient.getId().getResourceType());
		assertEquals("Patient", reincarnatedPatient.getId().getResourceType());
	}

	/**
	 * see #144 and #146
	 */
	@Test
	public void testParseContained() {

		FhirContext c = FhirContext.forDstu2();
		IParser parser = c.newXmlParser().setPrettyPrint(true);

		Observation o = new Observation();
		o.getCode().setText("obs text");

		Patient p = new Patient();
		p.addName().addFamily("patient family");
		o.getSubject().setResource(p);
		
		String enc = parser.encodeResourceToString(o);
		ourLog.info(enc);
		
		//@formatter:off
		assertThat(enc, stringContainsInOrder(
			"<Observation xmlns=\"http://hl7.org/fhir\">",
			"<contained>",
			"<Patient xmlns=\"http://hl7.org/fhir\">",
			"<id value=\"1\"/>",
			"</contained>",
			"<reference value=\"#1\"/>"
			));
		//@formatter:on
		
		o = parser.parseResource(Observation.class, enc);
		assertEquals("obs text", o.getCode().getText());
		
		assertNotNull(o.getSubject().getResource());
		p = (Patient) o.getSubject().getResource();
		assertEquals("patient family", p.getNameFirstRep().getFamilyAsSingleString());
	}

	/**
	 * See #113
	 */
	@Test
	public void testEncodeContainedResources() {
		
		MedicationPrescription medicationPrescript = new MedicationPrescription();
		
		String medId = "123";
		CodeableConceptDt codeDt = new CodeableConceptDt("urn:sys", "code1");

		// Adding medication to Contained.
		Medication medResource = new Medication();
		medResource.setCode(codeDt);
		medResource.setId("#" + String.valueOf(medId));
		ArrayList<IResource> medResList = new ArrayList<IResource>();
		medResList.add(medResource);
		ContainedDt medContainedDt = new ContainedDt();
		medContainedDt.setContainedResources(medResList);
		medicationPrescript.setContained(medContainedDt);

		// Medication reference. This should point to the contained resource.
		ResourceReferenceDt medRefDt = new ResourceReferenceDt("#" + medId);
		medRefDt.setDisplay("MedRef");
		medicationPrescript.setMedication(medRefDt);
		
		IParser p = ourCtx.newXmlParser().setPrettyPrint(true);
		String encoded = p.encodeResourceToString(medicationPrescript);
		ourLog.info(encoded);
		
		//@formatter:on
		assertThat(encoded, stringContainsInOrder(
			"<MedicationPrescription xmlns=\"http://hl7.org/fhir\">",
			"<contained>", 
			"<Medication xmlns=\"http://hl7.org/fhir\">", 
			"<id value=\"123\"/>", 
			"<code>", 
			"<coding>", 
			"<system value=\"urn:sys\"/>", 
			"<code value=\"code1\"/>", 
			"</coding>", 
			"</code>", 
			"</Medication>", 
			"</contained>", 
			"<medication>", 
			"<reference value=\"#123\"/>", 
			"<display value=\"MedRef\"/>", 
			"</medication>", 
			"</MedicationPrescription>"));
		//@formatter:off

	}
	
	/**
	 * See #113
	 */
	@Test
	public void testEncodeContainedResourcesManualContainUsingNonLocalId() {
		
		MedicationPrescription medicationPrescript = new MedicationPrescription();
		
		String medId = "123";
		CodeableConceptDt codeDt = new CodeableConceptDt("urn:sys", "code1");

		// Adding medication to Contained.
		Medication medResource = new Medication();
		medResource.setCode(codeDt);
		medResource.setId(String.valueOf(medId)); // ID does not start with '#'
		ArrayList<IResource> medResList = new ArrayList<IResource>();
		medResList.add(medResource);
		ContainedDt medContainedDt = new ContainedDt();
		medContainedDt.setContainedResources(medResList);
		medicationPrescript.setContained(medContainedDt);

		// Medication reference. This should point to the contained resource.
		ResourceReferenceDt medRefDt = new ResourceReferenceDt("#" + medId);
		medRefDt.setDisplay("MedRef");
		medicationPrescript.setMedication(medRefDt);
		
		IParser p = ourCtx.newXmlParser().setPrettyPrint(true);
		String encoded = p.encodeResourceToString(medicationPrescript);
		ourLog.info(encoded);
		
		//@formatter:on
		assertThat(encoded, stringContainsInOrder(
			"<MedicationPrescription xmlns=\"http://hl7.org/fhir\">",
			"<contained>", 
			"<Medication xmlns=\"http://hl7.org/fhir\">", 
			"<id value=\"123\"/>", 
			"<code>", 
			"<coding>", 
			"<system value=\"urn:sys\"/>", 
			"<code value=\"code1\"/>", 
			"</coding>", 
			"</code>", 
			"</Medication>", 
			"</contained>", 
			"<medication>", 
			"<reference value=\"#123\"/>", 
			"<display value=\"MedRef\"/>", 
			"</medication>", 
			"</MedicationPrescription>"));
		//@formatter:off

	}

	/**
	 * See #113
	 */
	@Test
	public void testEncodeContainedResourcesAutomatic() {
		
		MedicationPrescription medicationPrescript = new MedicationPrescription();
		String nameDisp = "MedRef";
		CodeableConceptDt codeDt = new CodeableConceptDt("urn:sys", "code1");
		
		// Adding medication to Contained.
		Medication medResource = new Medication();
		// No ID set
		medResource.setCode(codeDt);

		// Medication reference. This should point to the contained resource.
		ResourceReferenceDt medRefDt = new ResourceReferenceDt();
		medRefDt.setDisplay(nameDisp);
		// Resource reference set, but no ID
		medRefDt.setResource(medResource);
		medicationPrescript.setMedication(medRefDt);
		
		IParser p = ourCtx.newXmlParser().setPrettyPrint(true);
		String encoded = p.encodeResourceToString(medicationPrescript);
		ourLog.info(encoded);
		
		//@formatter:on
		assertThat(encoded, stringContainsInOrder(
			"<MedicationPrescription xmlns=\"http://hl7.org/fhir\">",
			"<contained>", 
			"<Medication xmlns=\"http://hl7.org/fhir\">", 
			"<id value=\"1\"/>", 
			"<code>", 
			"<coding>", 
			"<system value=\"urn:sys\"/>", 
			"<code value=\"code1\"/>", 
			"</coding>", 
			"</code>", 
			"</Medication>", 
			"</contained>", 
			"<medication>", 
			"<reference value=\"#1\"/>", 
			"<display value=\"MedRef\"/>", 
			"</medication>", 
			"</MedicationPrescription>"));
		//@formatter:off
	}

	@Test
	public void testEncodeAndParseSecurityLabels() {
		Patient p = new Patient();
		p.addName().addFamily("FAMILY");
		
		List<BaseCodingDt> labels = new ArrayList<BaseCodingDt>();
		labels.add(new CodingDt().setSystem("SYSTEM1").setCode("CODE1").setDisplay("DISPLAY1").setPrimary(true).setVersion("VERSION1"));
		labels.add(new CodingDt().setSystem("SYSTEM2").setCode("CODE2").setDisplay("DISPLAY2").setPrimary(false).setVersion("VERSION2"));
		
		ResourceMetadataKeyEnum.SECURITY_LABELS.put(p, labels);

		String enc = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(p);
		ourLog.info(enc);
		
		//@formatter:off
		assertThat(enc, stringContainsInOrder("<Patient xmlns=\"http://hl7.org/fhir\">", 
			"<meta>", 
			"<security>", 
			"<system value=\"SYSTEM1\"/>", 
			"<version value=\"VERSION1\"/>", 
			"<code value=\"CODE1\"/>", 
			"<display value=\"DISPLAY1\"/>", 
			"<primary value=\"true\"/>", 
			"</security>", 
			"<security>", 
			"<system value=\"SYSTEM2\"/>", 
			"<version value=\"VERSION2\"/>", 
			"<code value=\"CODE2\"/>", 
			"<display value=\"DISPLAY2\"/>", 
			"<primary value=\"false\"/>", 
			"</security>",
			"</meta>", 
			"<name>", 
			"<family value=\"FAMILY\"/>", 
			"</name>", 
			"</Patient>"));
		//@formatter:on
		
		Patient parsed = ourCtx.newXmlParser().parseResource(Patient.class, enc);
		List<BaseCodingDt> gotLabels = ResourceMetadataKeyEnum.SECURITY_LABELS.get(parsed);
		
		assertEquals(2,gotLabels.size());

		CodingDt label = (CodingDt) gotLabels.get(0);
		assertEquals("SYSTEM1", label.getSystem());
		assertEquals("CODE1", label.getCode());
		assertEquals("DISPLAY1", label.getDisplay());
		assertEquals(true, label.getPrimary());
		assertEquals("VERSION1", label.getVersion());

		label = (CodingDt) gotLabels.get(1);
		assertEquals("SYSTEM2", label.getSystem());
		assertEquals("CODE2", label.getCode());
		assertEquals("DISPLAY2", label.getDisplay());
		assertEquals(false, label.getPrimary());
		assertEquals("VERSION2", label.getVersion());
	}

	@Test
	public void testEncodeAndParseMetaProfileAndTags() {
		Patient p = new Patient();
		p.addName().addFamily("FAMILY");
		
		List<IdDt> profiles = new ArrayList<IdDt>();
		profiles.add(new IdDt("http://foo/Profile1"));
		profiles.add(new IdDt("http://foo/Profile2"));
		ResourceMetadataKeyEnum.PROFILES.put(p, profiles);

		TagList tagList = new TagList();
		tagList.addTag("scheme1", "term1", "label1");
		tagList.addTag("scheme2", "term2", "label2");
		ResourceMetadataKeyEnum.TAG_LIST.put(p, tagList);
		
		String enc = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(p);
		ourLog.info(enc);
		
		//@formatter:off
		assertThat(enc, stringContainsInOrder("<Patient xmlns=\"http://hl7.org/fhir\">", 
			"<meta>",
			"<meta>",
			"<profile value=\"http://foo/Profile1\"/>",
			"<profile value=\"http://foo/Profile2\"/>",
			"<tag>",
			"<system value=\"scheme1\"/>",
			"<code value=\"term1\"/>",
			"<display value=\"label1\"/>",
			"</tag>",
			"<tag>",
			"<system value=\"scheme2\"/>",
			"<code value=\"term2\"/>",
			"<display value=\"label2\"/>",
			"</tag>",
			"</meta>",
			"</meta>",
			"<name>",
			"<family value=\"FAMILY\"/>",
			"</name>", 
			"</Patient>"));
		//@formatter:on
		
		Patient parsed = ourCtx.newXmlParser().parseResource(Patient.class, enc);
		List<IdDt> gotLabels = ResourceMetadataKeyEnum.PROFILES.get(parsed);
		
		assertEquals(2,gotLabels.size());

		IdDt label = (IdDt) gotLabels.get(0);
		assertEquals("http://foo/Profile1", label.getValue());
		label = (IdDt) gotLabels.get(1);
		assertEquals("http://foo/Profile2", label.getValue());
		
		tagList = ResourceMetadataKeyEnum.TAG_LIST.get(parsed);
		assertEquals(2, tagList.size());
		
		assertEquals(new Tag("scheme1", "term1", "label1"), tagList.get(0));
		assertEquals(new Tag("scheme2", "term2", "label2"), tagList.get(1));
	}
	
	@Test
	public void testDuration() {
		Encounter enc = new Encounter();
		DurationDt duration = new DurationDt();
		duration.setUnits("day").setValue(123L);
		enc.setLength(duration);
		
		String str = ourCtx.newXmlParser().encodeResourceToString(enc);
		ourLog.info(str);
		
		assertThat(str, not(containsString("meta")));
		assertThat(str, containsString("<length><value value=\"123\"/><units value=\"day\"/></length>"));
	}
	
	@Test
	public void testParseBundleWithBinary() {
		// TODO: implement this test, make sure we handle ID and meta correctly in Binary
	}

	@Test
	public void testParseAndEncodeBundle() throws Exception {
		String content = IOUtils.toString(XmlParserDstu2Test.class.getResourceAsStream("/bundle-example.xml"));

		Bundle parsed = ourCtx.newXmlParser().parseBundle(content);
		assertEquals("http://example.com/base/Bundle/example/_history/1", parsed.getId().getValue());
		assertEquals("1", parsed.getResourceMetadata().get(ResourceMetadataKeyEnum.VERSION));
		assertEquals("1", parsed.getId().getVersionIdPart());
		assertEquals(new InstantDt("2014-08-18T01:43:30Z"), parsed.getResourceMetadata().get(ResourceMetadataKeyEnum.UPDATED));
		assertEquals("searchset", parsed.getType().getValue());
		assertEquals(3, parsed.getTotalResults().getValue().intValue());
		assertEquals("http://example.com/base", parsed.getLinkBase().getValue());
		assertEquals("https://example.com/base/MedicationPrescription?patient=347&searchId=ff15fd40-ff71-4b48-b366-09c706bed9d0&page=2", parsed.getLinkNext().getValue());
		assertEquals("https://example.com/base/MedicationPrescription?patient=347&_include=MedicationPrescription.medication", parsed.getLinkSelf().getValue());

		assertEquals(2, parsed.getEntries().size());
		assertEquals("http://foo?search", parsed.getEntries().get(0).getLinkSearch().getValue());

		MedicationPrescription p = (MedicationPrescription) parsed.getEntries().get(0).getResource();
		assertEquals("Patient/347", p.getPatient().getReference().getValue());
		assertEquals("2014-08-16T05:31:17Z", ResourceMetadataKeyEnum.UPDATED.get(p).getValueAsString());
		assertEquals("http://example.com/base/MedicationPrescription/3123/_history/1", p.getId().getValue());

		Medication m = (Medication) parsed.getEntries().get(1).getResource();
		assertEquals("http://example.com/base/Medication/example", m.getId().getValue());
		assertSame(p.getMedication().getResource(), m);

		String reencoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeBundleToString(parsed);
		ourLog.info(reencoded);

		Diff d = new Diff(new StringReader(content), new StringReader(reencoded));
		assertTrue(d.toString(), d.identical());

	}

	@Test
	public void testParseAndEncodeBundleNewStyle() throws Exception {
		String content = IOUtils.toString(XmlParserDstu2Test.class.getResourceAsStream("/bundle-example.xml"));

		IParser newXmlParser = ourCtx.newXmlParser();
		ca.uhn.fhir.model.dstu2.resource.Bundle parsed = newXmlParser.parseResource(ca.uhn.fhir.model.dstu2.resource.Bundle.class, content);
		assertEquals("http://example.com/base/Bundle/example/_history/1", parsed.getId().getValue());
		assertEquals("1", parsed.getResourceMetadata().get(ResourceMetadataKeyEnum.VERSION));
		assertEquals(new InstantDt("2014-08-18T01:43:30Z"), parsed.getResourceMetadata().get(ResourceMetadataKeyEnum.UPDATED));
		assertEquals("searchset", parsed.getType());
		assertEquals(3, parsed.getTotal().intValue());
		assertEquals("http://example.com/base", parsed.getBaseElement().getValueAsString());
		assertEquals("https://example.com/base/MedicationPrescription?patient=347&searchId=ff15fd40-ff71-4b48-b366-09c706bed9d0&page=2", parsed.getLink().get(0).getUrlElement().getValueAsString());
		assertEquals("https://example.com/base/MedicationPrescription?patient=347&_include=MedicationPrescription.medication", parsed.getLink().get(1).getUrlElement().getValueAsString());

		assertEquals(2, parsed.getEntry().size());
		assertEquals("http://foo?search", parsed.getEntry().get(0).getTransaction().getUrlElement().getValueAsString());

		MedicationPrescription p = (MedicationPrescription) parsed.getEntry().get(0).getResource();
		assertEquals("Patient/347", p.getPatient().getReference().getValue());
		assertEquals("2014-08-16T05:31:17Z", ResourceMetadataKeyEnum.UPDATED.get(p).getValueAsString());
		assertEquals("http://example.com/base/MedicationPrescription/3123/_history/1", p.getId().getValue());
//		assertEquals("3123", p.getId().getValue());

		Medication m = (Medication) parsed.getEntry().get(1).getResource();
		assertEquals("http://example.com/base/Medication/example", m.getId().getValue());
		assertSame(p.getMedication().getResource(), m);

		String reencoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(parsed);
		ourLog.info(reencoded);

		Diff d = new Diff(new StringReader(content), new StringReader(reencoded));
		assertTrue(d.toString(), d.identical());

	}

	
	@Test
	public void testEncodeAndParseBundleWithoutResourceIds() {
		Organization org = new Organization();
		org.addIdentifier().setSystem("urn:system").setValue("someval");
		
		Bundle bundle = Bundle.withSingleResource(org);
		String str = ourCtx.newXmlParser().encodeBundleToString(bundle);
		ourLog.info(str);
		
		Bundle parsed = ourCtx.newXmlParser().parseBundle(str);
		assertThat(parsed.getEntries().get(0).getResource().getId().getValue(), emptyOrNullString());
		assertTrue(parsed.getEntries().get(0).getResource().getId().isEmpty());
	}
	
	@Test
	public void testBundleWithBinary() {
		//@formatter:off
		String bundle = "<Bundle xmlns=\"http://hl7.org/fhir\">\n" + 
			"   <meta/>\n" + 
			"   <base value=\"http://localhost:52788\"/>\n" + 
			"   <total value=\"1\"/>\n" + 
			"   <link>\n" + 
			"      <relation value=\"self\"/>\n" + 
			"      <url value=\"http://localhost:52788/Binary?_pretty=true\"/>\n" + 
			"   </link>\n" + 
			"   <entry>\n" + 
			"      <resource>\n" + 
			"         <Binary xmlns=\"http://hl7.org/fhir\">\n" + 
			"            <id value=\"1\"/>\n" + 
			"            <meta/>\n" + 
			"            <contentType value=\"text/plain\"/>\n" + 
			"            <content value=\"AQIDBA==\"/>\n" + 
			"         </Binary>\n" + 
			"      </resource>\n" + 
			"   </entry>\n" + 
			"</Bundle>";
		//@formatter:on
		
		Bundle b = ourCtx.newXmlParser().parseBundle(bundle);
		assertEquals(1, b.getEntries().size());
		
		Binary bin = (Binary) b.getEntries().get(0).getResource();
		assertArrayEquals(new byte[] {1,2,3,4}, bin.getContent());
		
	}
	

	@Test
	public void testParseMetadata() throws Exception {
		//@formatter:off
		String bundle = "<Bundle xmlns=\"http://hl7.org/fhir\">\n" + 
			"   <base value=\"http://foo/fhirBase1\"/>\n" + 
			"   <total value=\"1\"/>\n" + 
			"   <link>\n" + 
			"      <relation value=\"self\"/>\n" + 
			"      <url value=\"http://localhost:52788/Binary?_pretty=true\"/>\n" + 
			"   </link>\n" + 
			"   <entry>\n" + 
			"      <base value=\"http://foo/fhirBase2\"/>\n" + 
			"      <resource>\n" + 
			"         <Patient xmlns=\"http://hl7.org/fhir\">\n" + 
			"            <id value=\"1\"/>\n" + 
			"            <meta>\n" +
			"               <versionId value=\"2\"/>\n" +
			"               <lastUpdated value=\"2001-02-22T11:22:33-05:00\"/>\n" +
			"            </meta>\n" + 
			"            <birthDate value=\"2012-01-02\"/>\n" + 
			"         </Patient>\n" + 
			"      </resource>\n" + 
			"      <search>\n" +
			"         <mode value=\"match\"/>\n" +
			"         <score value=\"0.123\"/>\n" +
			"      </search>\n" +
			"      <transaction>\n" +
			"         <method value=\"POST\"/>\n" +
			"         <url value=\"http://foo/Patient?identifier=value\"/>\n" +
			"      </transaction>\n" +
			"   </entry>\n" + 
			"</Bundle>";
		//@formatter:on
		
		Bundle b = ourCtx.newXmlParser().parseBundle(bundle);
		assertEquals(1, b.getEntries().size());
		
		Patient pt = (Patient) b.getEntries().get(0).getResource();
		assertEquals("http://foo/fhirBase2/Patient/1/_history/2", pt.getId().getValue());
		assertEquals("2012-01-02", pt.getBirthDateElement().getValueAsString());
		assertEquals("0.123", ResourceMetadataKeyEnum.ENTRY_SCORE.get(pt).getValueAsString());
		assertEquals("match", ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.get(pt).getCode());
		assertEquals("POST", ResourceMetadataKeyEnum.ENTRY_TRANSACTION_METHOD.get(pt).getCode());
		assertEquals("http://foo/Patient?identifier=value", ResourceMetadataKeyEnum.LINK_SEARCH.get(pt));
		assertEquals("2001-02-22T11:22:33-05:00", ResourceMetadataKeyEnum.UPDATED.get(pt).getValueAsString());
		
		Bundle toBundle = new Bundle();
		toBundle.getLinkBase().setValue("http://foo/fhirBase1");
		toBundle.getTotalResults().setValue(1);
		toBundle.getLinkSelf().setValue("http://localhost:52788/Binary?_pretty=true");
		
		toBundle.addResource(pt, ourCtx, "http://foo/fhirBase1");
		String reEncoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeBundleToString(toBundle);

		ourLog.info(reEncoded);
		
		Diff d = new Diff(new StringReader(bundle), new StringReader(reEncoded));
		assertTrue(d.toString(), d.identical());

	}

	/**
	 * See #103
	 */
	@Test
	public void testEncodeAndReEncodeContainedXml() {
		Composition comp = new Composition();
		comp.addSection().getContent().setResource(new AllergyIntolerance().setComment("Section0_Allergy0"));
		comp.addSection().getContent().setResource(new AllergyIntolerance().setComment("Section1_Allergy0"));
		comp.addSection().getContent().setResource(new AllergyIntolerance().setComment("Section2_Allergy0"));
		
		IParser parser = ourCtx.newXmlParser().setPrettyPrint(true);
		
		String string = parser.encodeResourceToString(comp);
		ourLog.info(string);

		Composition parsed = parser.parseResource(Composition.class, string);
		parsed.getSection().remove(0);

		string = parser.encodeResourceToString(parsed);
		ourLog.info(string);

		parsed = parser.parseResource(Composition.class, string);
		assertEquals(2, parsed.getContained().getContainedResources().size());
	}
	
	/**
	 * See #103
	 */
	@Test
	public void testEncodeAndReEncodeContainedJson() {
		Composition comp = new Composition();
		comp.addSection().getContent().setResource(new AllergyIntolerance().setComment("Section0_Allergy0"));
		comp.addSection().getContent().setResource(new AllergyIntolerance().setComment("Section1_Allergy0"));
		comp.addSection().getContent().setResource(new AllergyIntolerance().setComment("Section2_Allergy0"));
		
		IParser parser = ourCtx.newJsonParser().setPrettyPrint(true);
		
		String string = parser.encodeResourceToString(comp);
		ourLog.info(string);

		Composition parsed = parser.parseResource(Composition.class, string);
		parsed.getSection().remove(0);

		string = parser.encodeResourceToString(parsed);
		ourLog.info(string);

		parsed = parser.parseResource(Composition.class, string);
		assertEquals(2, parsed.getContained().getContainedResources().size());
	}

	@Test
	public void testEncodeAndParseExtensions() throws Exception {

		Patient patient = new Patient();
		patient.addIdentifier().setUse(IdentifierUseEnum.OFFICIAL).setSystem("urn:example").setValue("7000135");

		ExtensionDt ext = new ExtensionDt();
		ext.setUrl("http://example.com/extensions#someext");
		ext.setValue(new DateTimeDt("2011-01-02T11:13:15"));
		patient.addUndeclaredExtension(ext);

		ExtensionDt parent = new ExtensionDt().setUrl("http://example.com#parent");
		patient.addUndeclaredExtension(parent);
		ExtensionDt child1 = new ExtensionDt().setUrl("http://example.com#child").setValue(new StringDt("value1"));
		parent.addUndeclaredExtension(child1);
		ExtensionDt child2 = new ExtensionDt().setUrl("http://example.com#child").setValue(new StringDt("value2"));
		parent.addUndeclaredExtension(child2);

		ExtensionDt modExt = new ExtensionDt();
		modExt.setUrl("http://example.com/extensions#modext");
		modExt.setValue(new DateDt("1995-01-02"));
		modExt.setModifier(true);
		patient.addUndeclaredExtension(modExt);

		HumanNameDt name = patient.addName();
		name.addFamily("Blah");
		StringDt given = name.addGiven();
		given.setValue("Joe");
		ExtensionDt ext2 = new ExtensionDt().setUrl("http://examples.com#givenext").setValue(new StringDt("given"));
		given.addUndeclaredExtension(ext2);

		StringDt given2 = name.addGiven();
		given2.setValue("Shmoe");
		ExtensionDt given2ext = new ExtensionDt().setUrl("http://examples.com#givenext_parent");
		given2.addUndeclaredExtension(given2ext);
		given2ext.addUndeclaredExtension(new ExtensionDt().setUrl("http://examples.com#givenext_child").setValue(new StringDt("CHILD")));

		String output = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(patient);
		ourLog.info(output);

		String enc = ourCtx.newXmlParser().encodeResourceToString(patient);
		assertThat(enc, containsString("<Patient xmlns=\"http://hl7.org/fhir\"><extension url=\"http://example.com/extensions#someext\"><valueDateTime value=\"2011-01-02T11:13:15\"/></extension>"));
		assertThat(enc, containsString("<modifierExtension url=\"http://example.com/extensions#modext\"><valueDate value=\"1995-01-02\"/></modifierExtension>"));
		assertThat(
				enc,
				containsString("<extension url=\"http://example.com#parent\"><extension url=\"http://example.com#child\"><valueString value=\"value1\"/></extension><extension url=\"http://example.com#child\"><valueString value=\"value2\"/></extension></extension>"));
		assertThat(enc, containsString("<given value=\"Joe\"><extension url=\"http://examples.com#givenext\"><valueString value=\"given\"/></extension></given>"));
		assertThat(enc, containsString("<given value=\"Shmoe\"><extension url=\"http://examples.com#givenext_parent\"><extension url=\"http://examples.com#givenext_child\"><valueString value=\"CHILD\"/></extension></extension></given>"));
		
		/*
		 * Now parse this back
		 */

		Patient parsed = ourCtx.newXmlParser().parseResource(Patient.class, enc);
		ext = parsed.getUndeclaredExtensions().get(0);
		assertEquals("http://example.com/extensions#someext", ext.getUrl());
		assertEquals("2011-01-02T11:13:15", ((DateTimeDt) ext.getValue()).getValueAsString());

		parent = patient.getUndeclaredExtensions().get(1);
		assertEquals("http://example.com#parent", parent.getUrl());
		assertNull(parent.getValue());
		child1 = parent.getExtension().get(0);
		assertEquals("http://example.com#child", child1.getUrl());
		assertEquals("value1", ((StringDt) child1.getValue()).getValueAsString());
		child2 = parent.getExtension().get(1);
		assertEquals("http://example.com#child", child2.getUrl());
		assertEquals("value2", ((StringDt) child2.getValue()).getValueAsString());

		modExt = parsed.getUndeclaredModifierExtensions().get(0);
		assertEquals("http://example.com/extensions#modext", modExt.getUrl());
		assertEquals("1995-01-02", ((DateDt) modExt.getValue()).getValueAsString());

		name = parsed.getName().get(0);

		ext2 = name.getGiven().get(0).getUndeclaredExtensions().get(0);
		assertEquals("http://examples.com#givenext", ext2.getUrl());
		assertEquals("given", ((StringDt) ext2.getValue()).getValueAsString());

		given2ext = name.getGiven().get(1).getUndeclaredExtensions().get(0);
		assertEquals("http://examples.com#givenext_parent", given2ext.getUrl());
		assertNull(given2ext.getValue());
		ExtensionDt given2ext2 = given2ext.getExtension().get(0);
		assertEquals("http://examples.com#givenext_child", given2ext2.getUrl());
		assertEquals("CHILD", ((StringDt) given2ext2.getValue()).getValue());

	}

	
	
}
