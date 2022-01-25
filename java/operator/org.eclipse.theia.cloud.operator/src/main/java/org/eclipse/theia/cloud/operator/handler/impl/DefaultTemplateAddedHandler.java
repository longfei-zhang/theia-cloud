/********************************************************************************
 * Copyright (C) 2022 EclipseSource, Lockular, Ericsson, STMicroelectronics and 
 * others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.handler.impl;

import static org.eclipse.theia.cloud.operator.util.LogMessageUtil.formatLogMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.operator.handler.TemplateAddedHandler;
import org.eclipse.theia.cloud.operator.resource.TemplateSpec;
import org.eclipse.theia.cloud.operator.resource.TemplateSpecResource;
import org.eclipse.theia.cloud.operator.util.ResourceUtil;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;

public class DefaultTemplateAddedHandler implements TemplateAddedHandler {

    private static final Logger LOGGER = LogManager.getLogger(DefaultTemplateAddedHandler.class);

    protected static final String TEMPLATE_SERVICE_YAML = "/templateService.yaml";
    protected static final String TEMPLATE_DEPLOYMENT_YAML = "/templateDeployment.yaml";

    protected static final String PLACEHOLDER_SERVICENAME = "placeholder-servicename";
    protected static final String PLACEHOLDER_APP = "placeholder-app";
    protected static final String PLACEHOLDER_DEPLOYMENTNAME = "placeholder-depname";
    protected static final String PLACEHOLDER_NAMESPACE = "placeholder-namespace";
    protected static final String PLACEHOLDER_TEMPLATENAME = "placeholder-templatename";
    protected static final String PLACEHOLDER_IMAGE = "placeholder-image";

    protected static final String SERVICE_NAME = "-service-";
    protected static final String DEPLOYMENT_NAME = "-deployment-";

    @Override
    public void handle(DefaultKubernetesClient client, TemplateSpecResource template, String namespace,
	    String correlationId) {
	TemplateSpec spec = template.getSpec();
	LOGGER.info(formatLogMessage(correlationId, "Handling " + spec));

	String templateResourceName = template.getMetadata().getName();
	String templateResourceUID = template.getMetadata().getUid();
	String templateID = spec.getName();
	String image = spec.getImage();
	int instances = spec.getInstances();

	/* Get existing services for this template */
	List<Service> existingServices = getExistingServices(client, namespace, templateResourceName,
		templateResourceUID);

	/* Create missing services for this template */
	createMissingServices(client, namespace, correlationId, templateResourceName, templateResourceUID, templateID,
		instances, existingServices);

	/* Get existing deployments for this template */
	List<Deployment> existingDeployments = getExistingDeployments(client, namespace, templateResourceName,
		templateResourceUID);

	/* Create missing deployments for this template */
	createMissingDeployments(client, namespace, correlationId, templateResourceName, templateResourceUID,
		templateID, image, instances, existingDeployments);
    }

    private static boolean hasThisTemplateOwnerReference(List<OwnerReference> ownerReferences,
	    String templateResourceUID, String templateResourceName) {
	for (OwnerReference ownerReference : ownerReferences) {
	    if (templateResourceUID.equals(ownerReference.getUid())
		    && templateResourceName.equals(ownerReference.getName())) {
		return true;
	    }
	}
	return false;
    }

    protected List<Service> getExistingServices(DefaultKubernetesClient client, String namespace,
	    String templateResourceName, String templateResourceUID) {
	return client.services().inNamespace(namespace).list().getItems().stream()//
		.filter(service -> hasThisTemplateOwnerReference(service.getMetadata().getOwnerReferences(),
			templateResourceUID, templateResourceName))//
		.collect(Collectors.toList());
    }

    protected List<Deployment> getExistingDeployments(DefaultKubernetesClient client, String namespace,
	    String templateResourceName, String templateResourceUID) {
	return client.apps().deployments().inNamespace(namespace).list().getItems().stream()//
		.filter(deployment -> hasThisTemplateOwnerReference(deployment.getMetadata().getOwnerReferences(),
			templateResourceUID, templateResourceName))//
		.collect(Collectors.toList());
    }

    protected void createMissingServices(DefaultKubernetesClient client, String namespace, String correlationId,
	    String templateResourceName, String templateResourceUID, String templateID, int instances,
	    List<Service> existingServices) {
	if (existingServices.size() == 0) {
	    LOGGER.trace(formatLogMessage(correlationId, "No existing Services"));
	    for (int i = 1; i <= instances; i++) {
		createAndApplyService(client, namespace, correlationId, templateResourceName, templateResourceUID,
			templateID, i);
	    }
	} else {
	    List<Integer> missingInstances = IntStream.rangeClosed(1, instances).boxed().collect(Collectors.toList());
	    int namePrefixLength = (templateID + SERVICE_NAME).length();
	    for (Service service : existingServices) {
		String name = service.getMetadata().getName();
		String instance = name.substring(namePrefixLength);
		try {
		    missingInstances.remove(Integer.valueOf(instance));
		} catch (NumberFormatException e) {
		    LOGGER.error(formatLogMessage(correlationId, "Error while getting integer value of " + instance),
			    e);
		}
	    }
	    if (missingInstances.isEmpty()) {
		LOGGER.trace(formatLogMessage(correlationId, "All Services existing already"));
	    } else {
		LOGGER.trace(formatLogMessage(correlationId, "Some Services need to be created"));
	    }
	    for (int i : missingInstances) {
		createAndApplyService(client, namespace, correlationId, templateResourceName, templateResourceUID,
			templateID, i);
	    }
	}
    }

    protected void createMissingDeployments(DefaultKubernetesClient client, String namespace, String correlationId,
	    String templateResourceName, String templateResourceUID, String templateID, String image, int instances,
	    List<Deployment> existingDeployments) {
	if (existingDeployments.size() == 0) {
	    LOGGER.trace(formatLogMessage(correlationId, "No existing Deployments"));
	    for (int i = 1; i <= instances; i++) {
		createAndApplyDeployment(client, namespace, correlationId, templateResourceName, templateResourceUID,
			templateID, image, i);
	    }
	} else {
	    List<Integer> missingDeployments = IntStream.rangeClosed(1, instances).boxed().collect(Collectors.toList());
	    int namePrefixLength = (templateID + DEPLOYMENT_NAME).length();
	    for (Deployment deployment : existingDeployments) {
		String name = deployment.getMetadata().getName();
		String instance = name.substring(namePrefixLength);
		try {
		    missingDeployments.remove(Integer.valueOf(instance));
		} catch (NumberFormatException e) {
		    LOGGER.error(formatLogMessage(correlationId, "Error while getting integer value of " + instance),
			    e);
		}
	    }
	    if (missingDeployments.isEmpty()) {
		LOGGER.trace(formatLogMessage(correlationId, "All Deployments existing already"));
	    } else {
		LOGGER.trace(formatLogMessage(correlationId, "Some Deployments need to be created"));
	    }
	    for (int i : missingDeployments) {
		createAndApplyDeployment(client, namespace, correlationId, templateResourceName, templateResourceUID,
			templateID, image, i);
	    }
	}
    }

    protected void createAndApplyService(DefaultKubernetesClient client, String namespace, String correlationId,
	    String templateResourceName, String templateResourceUID, String templateID, int instance) {
	/* create yaml based on template */
	Map<String, String> replacements = getServiceReplacements(templateID, instance, namespace);
	String serviceYaml;
	try {
	    serviceYaml = ResourceUtil.readResourceAndReplacePlaceholders(DefaultTemplateAddedHandler.class,
		    TEMPLATE_SERVICE_YAML, replacements, correlationId);
	} catch (IOException | URISyntaxException e) {
	    LOGGER.error(
		    formatLogMessage(correlationId, "Error while adjusting template for instance number " + instance),
		    e);
	    return;
	}

	try (ByteArrayInputStream inputStream = new ByteArrayInputStream(serviceYaml.getBytes())) {
	    /* prepare new service */
	    NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> services = client.services()
		    .inNamespace(namespace);
	    LOGGER.trace(formatLogMessage(correlationId,
		    "Loading new service for instance number " + instance + " :\n" + serviceYaml));
	    Service newService = services.load(inputStream).get();
	    newService.getMetadata().getOwnerReferences().get(0).setUid(templateResourceUID);
	    newService.getMetadata().getOwnerReferences().get(0).setName(templateResourceName);

	    /* apply new deployment */
	    LOGGER.trace(formatLogMessage(correlationId, "Creating new service for instance number " + instance));
	    services.create(newService);
	    LOGGER.info(formatLogMessage(correlationId, "Created a new service for instance number " + instance));
	} catch (IOException e) {
	    LOGGER.error(formatLogMessage(correlationId, "Error with template stream for instance number " + instance),
		    e);
	    return;
	}
	return;
    }

    protected void createAndApplyDeployment(DefaultKubernetesClient client, String namespace, String correlationId,
	    String templateResourceName, String templateResourceUID, String templateID, String image, int instance) {
	/* create yaml based on template */
	Map<String, String> replacements = getDeploymentsReplacements(templateID, image, instance, namespace);
	String deploymentYaml;
	try {
	    deploymentYaml = ResourceUtil.readResourceAndReplacePlaceholders(DefaultTemplateAddedHandler.class,
		    TEMPLATE_DEPLOYMENT_YAML, replacements, correlationId);
	} catch (IOException | URISyntaxException e) {
	    LOGGER.error(
		    formatLogMessage(correlationId, "Error while adjusting template for instance number " + instance),
		    e);
	    return;
	}

	try (ByteArrayInputStream inputStream = new ByteArrayInputStream(deploymentYaml.getBytes())) {
	    /* prepare new deployment */
	    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = client
		    .apps().deployments().inNamespace(namespace);
	    LOGGER.trace(formatLogMessage(correlationId,
		    "Loading new deployment for instance number " + instance + " :\n" + deploymentYaml));
	    Deployment newDeployment = deployments.load(inputStream).get();
	    newDeployment.getMetadata().getOwnerReferences().get(0).setUid(templateResourceUID);
	    newDeployment.getMetadata().getOwnerReferences().get(0).setName(templateResourceName);

	    /* apply new deployment */
	    LOGGER.trace(formatLogMessage(correlationId, "Creating new deployment for instance number " + instance));
	    deployments.create(newDeployment);
	    LOGGER.info(formatLogMessage(correlationId, "Created a new deployment for instance number " + instance));
	} catch (IOException e) {
	    LOGGER.error(formatLogMessage(correlationId, "Error with template stream for instance number " + instance),
		    e);
	    return;
	}
	return;
    }

    protected Map<String, String> getServiceReplacements(String templateID, int instance, String namespace) {
	Map<String, String> replacements = new LinkedHashMap<String, String>();
	replacements.put(PLACEHOLDER_SERVICENAME, templateID + SERVICE_NAME + instance);
	replacements.put(PLACEHOLDER_APP, templateID + "-" + instance);
	replacements.put(PLACEHOLDER_NAMESPACE, namespace);
	return replacements;
    }

    protected Map<String, String> getDeploymentsReplacements(String templateID, String image, int instance,
	    String namespace) {
	Map<String, String> replacements = new LinkedHashMap<String, String>();
	replacements.put(PLACEHOLDER_DEPLOYMENTNAME, templateID + DEPLOYMENT_NAME + instance);
	replacements.put(PLACEHOLDER_NAMESPACE, namespace);
	replacements.put(PLACEHOLDER_APP, templateID + "-" + instance);
	replacements.put(PLACEHOLDER_TEMPLATENAME, templateID);
	replacements.put(PLACEHOLDER_IMAGE, image);
	return replacements;
    }

}