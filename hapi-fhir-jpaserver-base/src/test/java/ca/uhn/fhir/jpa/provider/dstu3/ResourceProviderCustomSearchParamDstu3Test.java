package ca.uhn.fhir.jpa.provider.dstu3;

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.CapabilityStatement;
import org.hl7.fhir.dstu3.model.CodeType;
import org.hl7.fhir.dstu3.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.dstu3.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.dstu3.model.CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent;
import org.hl7.fhir.dstu3.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Observation.ObservationStatus;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.SearchParameter;
import org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType;
import org.hl7.fhir.instance.model.api.IIdType;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import ca.uhn.fhir.jpa.dao.BaseHapiFhirDao;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.entity.ResourceTable;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.TestUtil;

public class ResourceProviderCustomSearchParamDstu3Test extends BaseResourceProviderDstu3Test {

	@Override
	@After
	public void after() throws Exception {
		super.after();

		myDaoConfig.setDefaultSearchParamsCanBeOverridden(new DaoConfig().isDefaultSearchParamsCanBeOverridden());
	}

	@Override
	public void before() throws Exception {
		super.before();
	}

	@Test
	public void saveCreateSearchParamInvalidWithMissingStatus() throws IOException {
		SearchParameter sp = new SearchParameter();
		sp.setCode("foo");
		sp.setExpression("Patient.gender");
		sp.setXpathUsage(XPathUsageType.NORMAL);
		sp.setTitle("Foo Param");

		try {
			ourClient.create().resource(sp).execute();
			fail();
		} catch (UnprocessableEntityException e) {
			assertEquals("HTTP 422 Unprocessable Entity: SearchParameter.status is missing or invalid: null", e.getMessage());
		}
	}

	@Test
	public void testSearchForExtension() {
		SearchParameter eyeColourSp = new SearchParameter();
		eyeColourSp.addBase("Patient");
		eyeColourSp.setCode("eyecolour");
		eyeColourSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
		eyeColourSp.setTitle("Eye Colour");
		eyeColourSp.setExpression("Patient.extension('http://acme.org/eyecolour')");
		eyeColourSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
		eyeColourSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);

		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(eyeColourSp));

		ourClient
				.create()
				.resource(eyeColourSp)
				.execute();

		// mySearchParamRegsitry.forceRefresh();

		Patient p1 = new Patient();
		p1.setActive(true);
		p1.addExtension().setUrl("http://acme.org/eyecolour").setValue(new CodeType("blue"));
		IIdType p1id = myPatientDao.create(p1).getId().toUnqualifiedVersionless();

		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(p1));

		Patient p2 = new Patient();
		p2.setActive(true);
		p2.addExtension().setUrl("http://acme.org/eyecolour").setValue(new CodeType("green"));
		IIdType p2id = myPatientDao.create(p2).getId().toUnqualifiedVersionless();

		Bundle bundle = ourClient
				.search()
				.forResource(Patient.class)
				.where(new TokenClientParam("eyecolour").exactly().code("blue"))
				.returnBundle(Bundle.class)
				.execute();

		ourLog.info(myFhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));

		List<String> foundResources = toUnqualifiedVersionlessIdValues(bundle);
		assertThat(foundResources, contains(p1id.getValue()));

	}

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ResourceProviderCustomSearchParamDstu3Test.class);

	@Override
	@Before
	public void beforeResetConfig() {
		super.beforeResetConfig();

		myDaoConfig.setDefaultSearchParamsCanBeOverridden(new DaoConfig().isDefaultSearchParamsCanBeOverridden());
		mySearchParamRegsitry.forceRefresh();
	}

	@Test
	public void testConformanceOverrideAllowed() {
		myDaoConfig.setDefaultSearchParamsCanBeOverridden(true);

		CapabilityStatement conformance = ourClient
				.fetchConformance()
				.ofType(CapabilityStatement.class)
				.execute();
		Map<String, CapabilityStatementRestResourceSearchParamComponent> map = extractSearchParams(conformance, "Patient");

		CapabilityStatementRestResourceSearchParamComponent param = map.get("foo");
		assertNull(param);

		param = map.get("gender");
		assertNotNull(param);

		TransactionTemplate txTemplate = newTxTemplate();
		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theStatus) {
				// Add a custom search parameter
				SearchParameter fooSp = new SearchParameter();
				fooSp.addBase("Patient");
				fooSp.setCode("foo");
				fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
				fooSp.setTitle("FOO SP");
				fooSp.setExpression("Patient.gender");
				fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
				fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);
				mySearchParameterDao.create(fooSp, mySrd);
			}
		});

		// Disable an existing parameter
		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theStatus) {
				SearchParameter fooSp = new SearchParameter();
				fooSp.addBase("Patient");
				fooSp.setCode("gender");
				fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
				fooSp.setTitle("Gender");
				fooSp.setExpression("Patient.gender");
				fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
				fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.RETIRED);
				mySearchParameterDao.create(fooSp, mySrd);
			}
		});

		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theStatus) {
				mySearchParamRegsitry.forceRefresh();
			}
		});

		conformance = ourClient
				.fetchConformance()
				.ofType(CapabilityStatement.class)
				.execute();
		map = extractSearchParams(conformance, "Patient");

		param = map.get("foo");
		assertEquals("foo", param.getName());

		param = map.get("gender");
		assertNull(param);

	}

	@Test
	public void testConformanceOverrideNotAllowed() {
		myDaoConfig.setDefaultSearchParamsCanBeOverridden(false);

		CapabilityStatement conformance = ourClient
				.fetchConformance()
				.ofType(CapabilityStatement.class)
				.execute();
		Map<String, CapabilityStatementRestResourceSearchParamComponent> map = extractSearchParams(conformance, "Patient");

		CapabilityStatementRestResourceSearchParamComponent param = map.get("foo");
		assertNull(param);

		param = map.get("gender");
		assertNotNull(param);

		// Add a custom search parameter
		SearchParameter fooSp = new SearchParameter();
		fooSp.addBase("Patient");
		fooSp.setCode("foo");
		fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
		fooSp.setTitle("FOO SP");
		fooSp.setExpression("Patient.gender");
		fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
		fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);
		mySearchParameterDao.create(fooSp, mySrd);

		// Disable an existing parameter
		fooSp = new SearchParameter();
		fooSp.addBase("Patient");
		fooSp.setCode("gender");
		fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
		fooSp.setTitle("Gender");
		fooSp.setExpression("Patient.gender");
		fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
		fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.RETIRED);
		mySearchParameterDao.create(fooSp, mySrd);

		mySearchParamRegsitry.forceRefresh();

		conformance = ourClient
				.fetchConformance()
				.ofType(CapabilityStatement.class)
				.execute();
		map = extractSearchParams(conformance, "Patient");

		param = map.get("foo");
		assertEquals("foo", param.getName());

		param = map.get("gender");
		assertNotNull(param);

	}

	private Map<String, CapabilityStatementRestResourceSearchParamComponent> extractSearchParams(CapabilityStatement conformance, String resType) {
		Map<String, CapabilityStatementRestResourceSearchParamComponent> map = new HashMap<String, CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent>();
		for (CapabilityStatementRestComponent nextRest : conformance.getRest()) {
			for (CapabilityStatementRestResourceComponent nextResource : nextRest.getResource()) {
				if (!resType.equals(nextResource.getType())) {
					continue;
				}
				for (CapabilityStatementRestResourceSearchParamComponent nextParam : nextResource.getSearchParam()) {
					map.put(nextParam.getName(), nextParam);
				}
			}
		}
		return map;
	}

	@SuppressWarnings("unused")
	@Test
	public void testSearchWithCustomParam() {

		SearchParameter fooSp = new SearchParameter();
		fooSp.addBase("Patient");
		fooSp.setCode("foo");
		fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
		fooSp.setTitle("FOO SP");
		fooSp.setExpression("Patient.gender");
		fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
		fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);
		mySearchParameterDao.create(fooSp, mySrd);

		mySearchParamRegsitry.forceRefresh();

		Patient pat = new Patient();
		pat.setGender(AdministrativeGender.MALE);
		IIdType patId = myPatientDao.create(pat, mySrd).getId().toUnqualifiedVersionless();

		Patient pat2 = new Patient();
		pat2.setGender(AdministrativeGender.FEMALE);
		IIdType patId2 = myPatientDao.create(pat2, mySrd).getId().toUnqualifiedVersionless();

		SearchParameterMap map;
		IBundleProvider results;
		List<String> foundResources;
		Bundle result;

		result = ourClient
				.search()
				.forResource(Patient.class)
				.where(new TokenClientParam("foo").exactly().code("male"))
				.returnBundle(Bundle.class)
				.execute();

		foundResources = toUnqualifiedVersionlessIdValues(result);
		assertThat(foundResources, contains(patId.getValue()));

	}

	@Test
	public void testCreatingParamMarksCorrectResourcesForReindexing() {
		Patient pat = new Patient();
		pat.setGender(AdministrativeGender.MALE);
		IIdType patId = myPatientDao.create(pat, mySrd).getId().toUnqualifiedVersionless();

		Observation obs2 = new Observation();
		obs2.setStatus(ObservationStatus.FINAL);
		IIdType obsId = myObservationDao.create(obs2, mySrd).getId().toUnqualifiedVersionless();

		ResourceTable res = myResourceTableDao.findOne(patId.getIdPartAsLong());
		assertEquals(BaseHapiFhirDao.INDEX_STATUS_INDEXED, res.getIndexStatus().longValue());
		res = myResourceTableDao.findOne(obsId.getIdPartAsLong());
		assertEquals(BaseHapiFhirDao.INDEX_STATUS_INDEXED, res.getIndexStatus().longValue());

		SearchParameter fooSp = new SearchParameter();
		fooSp.addBase("Patient");
		fooSp.setCode("foo");
		fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.TOKEN);
		fooSp.setTitle("FOO SP");
		fooSp.setExpression("Patient.gender");
		fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
		fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);
		mySearchParameterDao.create(fooSp, mySrd);

		res = myResourceTableDao.findOne(patId.getIdPartAsLong());
		assertEquals(null, res.getIndexStatus());
		res = myResourceTableDao.findOne(obsId.getIdPartAsLong());
		assertEquals(BaseHapiFhirDao.INDEX_STATUS_INDEXED, res.getIndexStatus().longValue());

	}

	@SuppressWarnings("unused")
	@Test
	public void testSearchQualifiedWithCustomReferenceParam() {

		SearchParameter fooSp = new SearchParameter();
		fooSp.addBase("Patient");
		fooSp.setCode("foo");
		fooSp.setType(org.hl7.fhir.dstu3.model.Enumerations.SearchParamType.REFERENCE);
		fooSp.setTitle("FOO SP");
		fooSp.setExpression("Observation.subject");
		fooSp.setXpathUsage(org.hl7.fhir.dstu3.model.SearchParameter.XPathUsageType.NORMAL);
		fooSp.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);
		mySearchParameterDao.create(fooSp, mySrd);

		mySearchParamRegsitry.forceRefresh();

		Patient pat = new Patient();
		pat.setGender(AdministrativeGender.MALE);
		IIdType patId = myPatientDao.create(pat, mySrd).getId().toUnqualifiedVersionless();

		Observation obs1 = new Observation();
		obs1.getSubject().setReferenceElement(patId);
		IIdType obsId1 = myObservationDao.create(obs1, mySrd).getId().toUnqualifiedVersionless();

		Observation obs2 = new Observation();
		obs2.setStatus(org.hl7.fhir.dstu3.model.Observation.ObservationStatus.FINAL);
		IIdType obsId2 = myObservationDao.create(obs2, mySrd).getId().toUnqualifiedVersionless();

		SearchParameterMap map;
		IBundleProvider results;
		List<String> foundResources;
		Bundle result;

		result = ourClient
				.search()
				.forResource(Observation.class)
				.where(new ReferenceClientParam("foo").hasChainedProperty(Patient.GENDER.exactly().code("male")))
				.returnBundle(Bundle.class)
				.execute();
		foundResources = toUnqualifiedVersionlessIdValues(result);
		assertThat(foundResources, contains(obsId1.getValue()));

	}

	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

}
