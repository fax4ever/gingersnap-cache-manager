package io.gingersnapproject.k8s;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.gingersnapproject.k8s.configuration.KubernetesConfiguration;
import io.quarkus.arc.lookup.LookupUnlessProperty;

@ApplicationScoped
public class KubernetesClientProducer {

   private static final Logger log = LoggerFactory.getLogger(KubernetesClientProducer.class);

   @Inject
   KubernetesConfiguration configuration;

   private volatile KubernetesClient client;

   @Produces
   @LookupUnlessProperty(name = "gingersnap.k8s.lazy-config-map", stringValue = "")
   @LookupUnlessProperty(name = "gingersnap.k8s.eager-config-map", stringValue = "")
   public KubernetesClient kubernetesClient() {
      if (client == null) {
         log.info("Creating Kubernetes client");

         client = new KubernetesClientBuilder()
                 .withConfig(
                         new ConfigBuilder()
                                 .withNamespace(configuration.namespace())
                                 .build()
                 )
                 .build();
      }
      return client;
   }
}
