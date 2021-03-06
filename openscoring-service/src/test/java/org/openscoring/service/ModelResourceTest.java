/*
 * Copyright (c) 2014 Villu Ruusmann
 *
 * This file is part of Openscoring
 *
 * Openscoring is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Openscoring is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Openscoring.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.openscoring.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.ModelEvaluator;
import org.jpmml.evaluator.TypeUtil;
import org.jpmml.evaluator.VerificationUtil;
import org.jpmml.model.visitors.LocatorNullifier;
import org.junit.Test;
import org.openscoring.common.BatchEvaluationRequest;
import org.openscoring.common.BatchEvaluationResponse;
import org.openscoring.common.EvaluationRequest;
import org.openscoring.common.EvaluationResponse;
import org.supercsv.prefs.CsvPreference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModelResourceTest {

	@Test
	public void decisionTreeIris() throws Exception {
		ModelResource service = createService();

		deploy(service, "DecisionTreeIris");

		List<EvaluationRequest> requests = loadRequest("Iris");
		List<EvaluationResponse> responses = evaluateBatch(service, "DecisionTreeIris", requests);

		assertEquals(150, requests.size());
		assertEquals(150, responses.size());

		List<EvaluationResponse> expectedResponses = loadResponse("DecisionTreeIris");

		compare(expectedResponses, responses);

		undeploy(service, "DecisionTreeIris");
	}

	@Test
	public void associationRulesShopping() throws Exception {
		ModelResource service = createService();

		deploy(service, "AssociationRulesShopping");

		List<EvaluationRequest> requests = loadRequest("Shopping");
		List<EvaluationResponse> responses = evaluateBatch(service, "AssociationRulesShopping", requests);

		assertEquals(13, requests.size());
		assertEquals(5, responses.size());

		List<EvaluationResponse> expectedResponses = loadResponse("AssociationRulesShopping");

		compare(expectedResponses, responses);

		List<EvaluationRequest> aggregatedRequests = ModelResource.aggregateRequests(FieldName.create("transaction"), requests);
		List<EvaluationResponse> aggregatedResponses = evaluateBatch(service, "AssociationRulesShopping", aggregatedRequests);

		assertEquals(5, aggregatedRequests.size());
		assertEquals(5, aggregatedResponses.size());

		compare(expectedResponses, aggregatedResponses);

		undeploy(service, "AssociationRulesShopping");
	}

	static
	private ModelResource createService(){
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("modelRegistry.visitorClasses", Collections.singletonList(LocatorNullifier.class.getName()));
		properties.put("modelRegistry.validate", Boolean.FALSE);

		Config config = ConfigFactory.parseMap(properties);

		ModelRegistry modelRegistry = new ModelRegistry(config);

		MetricRegistry metricRegistry = new MetricRegistry();

		ModelResource service = new ModelResource(modelRegistry, metricRegistry);

		return service;
	}

	static
	private void deploy(ModelResource service, String id) throws IOException {
		ModelRegistry modelRegistry = service.getModelRegistry();
		MetricRegistry metricRegistry = service.getMetricRegistry();

		// XXX
		modelRegistry.put(id, new Model());

		InputStream is = openPMML(id);

		try {
			service.deploy(id, is);
		} finally {
			is.close();
		}

		Model model = modelRegistry.get(id);

		assertNotNull(model);

		ModelEvaluator<?> evaluator = model.getEvaluator();

		PMML pmml = evaluator.getPMML();

		assertNull(pmml.getLocator());

		Map<String, Metric> metrics = metricRegistry.getMetrics();

		assertTrue(metrics.isEmpty());
	}

	static
	private List<EvaluationResponse> evaluateBatch(ModelResource service, String id, List<EvaluationRequest> requests){
		MetricRegistry metricRegistry = service.getMetricRegistry();

		BatchEvaluationRequest request = new BatchEvaluationRequest();
		request.setRequests(requests);

		BatchEvaluationResponse response = service.evaluateBatch(id, request);

		Map<String, Metric> metrics = metricRegistry.getMetrics();

		assertFalse(metrics.isEmpty());

		return response.getResponses();
	}

	static
	private void undeploy(ModelResource service, String id){
		ModelRegistry modelRegistry = service.getModelRegistry();
		MetricRegistry metricRegistry = service.getMetricRegistry();

		service.undeploy(id);

		Model model = modelRegistry.get(id);

		assertNull(model);

		Map<String, Metric> metrics = metricRegistry.getMetrics();

		assertTrue(metrics.isEmpty());
	}

	static
	private InputStream openPMML(String id){
		return ModelResourceTest.class.getResourceAsStream("/pmml/" + id + ".pmml");
	}

	static
	private InputStream openCSV(String id){
		return ModelResourceTest.class.getResourceAsStream("/csv/" + id + ".csv");
	}

	static
	private List<EvaluationRequest> loadRequest(String id) throws Exception {
		InputStream is = openCSV(id);

		try {
			CsvUtil.Table<EvaluationRequest> table;

			BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

			try {
				table = CsvUtil.readTable(reader, CsvPreference.TAB_PREFERENCE);
			} finally {
				reader.close();
			}

			return table.getRows();
		} finally {
			is.close();
		}
	}

	static
	private List<EvaluationResponse> loadResponse(String id) throws Exception {
		return convert(loadRequest(id));
	}

	static
	private List<EvaluationResponse> convert(List<EvaluationRequest> requests){
		List<EvaluationResponse> responses = new ArrayList<>();

		for(EvaluationRequest request : requests){
			EvaluationResponse response = new EvaluationResponse(request.getId());
			response.setResult(request.getArguments());

			responses.add(response);
		}

		return responses;
	}

	static
	private void compare(List<EvaluationResponse> expectedResponses, List<EvaluationResponse> actualResponses){
		assertEquals(expectedResponses.size(), actualResponses.size());

		for(int i = 0; i < expectedResponses.size(); i++){
			EvaluationResponse expectedResponse = expectedResponses.get(i);
			EvaluationResponse actualResponse = actualResponses.get(i);

			compare(expectedResponse.getResult(), actualResponse.getResult());
		}
	}

	static
	private void compare(Map<String, ?> expectedResult, Map<String, ?> actualResult){
		assertEquals(expectedResult.size(), actualResult.size());

		Set<String> keys = expectedResult.keySet();
		for(String key : keys){
			String expectedValue = (String)expectedResult.get(key);
			Object actualValue = actualResult.get(key);

			if(actualValue instanceof Collection){

				if(expectedValue.startsWith("[") && expectedValue.endsWith("]")){
					expectedValue = expectedValue.substring(1, expectedValue.length() - 1);

					String[] expectedElements = (expectedValue.length() > 0 ? expectedValue.split(",\\s?") : new String[0]);

					assertTrue(acceptable(Arrays.asList(expectedElements), (List<?>)actualValue));
				} else

				{
					fail();
				}
			} else

			{
				assertTrue(acceptable(expectedValue, actualValue));
			}
		}
	}

	static
	private boolean acceptable(List<String> expectedValues, List<?> actualValues){

		if(expectedValues.size() != actualValues.size()){
			return false;
		}

		boolean result = true;

		for(int i = 0; i < expectedValues.size(); i++){
			String expectedValue = expectedValues.get(i);
			Object actualValue = actualValues.get(i);

			result &= acceptable(expectedValue, actualValue);
		}

		return result;
	}

	static
	private boolean acceptable(String expectedValue, Object actualValue){
		return VerificationUtil.acceptable(TypeUtil.parse(TypeUtil.getDataType(actualValue), expectedValue), actualValue, ModelResourceTest.precision, ModelResourceTest.zeroThreshold);
	}

	private static final double precision = 1d / (1000 * 1000);

	private static final double zeroThreshold = precision;
}