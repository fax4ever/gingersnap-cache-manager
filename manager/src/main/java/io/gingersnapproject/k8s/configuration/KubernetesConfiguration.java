package io.gingersnapproject.k8s.configuration;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "gingersnap.k8s")
public interface KubernetesConfiguration {

   @WithName("lazy-config-map")
   Optional<String> lazyConfigMapName();

   @WithName("eager-config-map")
   Optional<String> eagerConfigMapName();

   @WithDefault("default")
   String namespace();

   @WithDefault("false")
   @WithName("service-binding-required")
   boolean serviceBindingRequired();
}
