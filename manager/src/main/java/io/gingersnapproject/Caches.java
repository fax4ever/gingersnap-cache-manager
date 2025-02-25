package io.gingersnapproject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import io.gingersnapproject.configuration.Rule;
import org.infinispan.commons.dataconversion.internal.Json;
import org.infinispan.util.KeyValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.gingersnapproject.configuration.EagerRule;
import io.gingersnapproject.configuration.RuleEvents;
import io.gingersnapproject.configuration.RuleManager;
import io.gingersnapproject.database.DatabaseHandler;
import io.gingersnapproject.database.model.ForeignKey;
import io.gingersnapproject.database.model.Table;
import io.gingersnapproject.metrics.CacheAccessRecord;
import io.gingersnapproject.metrics.CacheManagerMetrics;
import io.gingersnapproject.mutiny.UniItem;
import io.gingersnapproject.search.IndexingHandler;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.groups.UniJoin;

@ApplicationScoped
public class Caches {
   private static final Logger log = LoggerFactory.getLogger(Caches.class);

   private static final Uni<Map<String, String>> EMPTY_MAP_UNI = Uni.createFrom().item(Collections.emptyMap());
   @Inject
   CacheManagerMetrics metrics;
   private final ConcurrentMap<String, LoadingCache<String, Uni<String>>> maps = new ConcurrentHashMap<>();

   @Inject
   DatabaseHandler databaseHandler;
   @Inject
   RuleManager ruleManager;
   @Inject
   IndexingHandler indexingHandler;

   private LoadingCache<String, Uni<String>> getOrCreateMap(String name) {
      return maps.computeIfAbsent(name, this::createLoadingCache);
   }

   private void replace(String name, String key, Uni<String> prev, String value) {
      var cache = maps.get(name);
      if (cache != null) {
         cache.asMap().replace(key, prev, UniItem.fromItem(value));
      }
   }

   private void actualRemove(String name, String key, Uni<String> value) {
      var cache = maps.get(name);
      if (cache != null) {
         cache.asMap().remove(key, value);
      }
   }

   public Uni<String> denormalizedPut(String name, String key, String value) {
      return denormalize(name, value).chain(denormalizedVal -> put(name, key, denormalizedVal));
   }

   public Uni<String> denormalize(String ruleName, String value) {
      EagerRule rule = ruleManager.eagerRules().get(ruleName);
      Table table = databaseHandler.table(rule.connector().table());
      Json json = Json.read(value);
      if (!rule.expandEntity() || table.foreignKeys().isEmpty())
         return Uni.createFrom().item(json.toString());

      boolean denormalize = false;
      UniJoin.Builder<Json> builder = Uni.join().builder();
      for (ForeignKey fk : table.foreignKeys()) {
         // TODO: handle composite fks
         String fkColumn = fk.columns().get(0);
         if (json.at(fkColumn) == null)
            continue;

         Json fkJson = json.atDel(fkColumn);
         if (fkJson != null && !fkJson.isNull()) {
            denormalize = true;
            String fkId = fkJson.asString();
            String fkRule = databaseHandler.tableToRuleName(fk.refTable());
            if (fkRule == null)
               throw new IllegalStateException(String.format("No eager-rule defined for table '%s', unable to resolve ForeignKey", fk.refTable()));

            var uni = getOrCreateMap(fkRule)
                    .get(fkId)
                    .map(Json::read)
                    .map(j -> json.set(fkColumn, j));
            builder.add(uni);
         }
      }

      if (denormalize)
         return builder.joinAll().andFailFast().chain(() -> Uni.createFrom().item(json.toString()));

      return Uni.createFrom().item(json.toString());
   }

   public Uni<String> put(String name, String key, String value) {
      Uni<String> indexUni = indexingHandler.put(name, key, value)
            .map(___ -> value)
            // Make sure subsequent subscriptions don't update index again
            .memoize().indefinitely();
      getOrCreateMap(name)
            .put(key, indexUni);
      // TODO: technically only want to do this on caller subscribing
      indexUni.subscribe()
            .with(s -> replace(name, key, indexUni, value), t -> actualRemove(name, key, indexUni));
      return indexUni;
   }

   public Uni<String> putAll(String name, Map<String, String> values) {
      Uni<String> bulkIndexingOperation = indexingHandler.putAll(name, values)
            .memoize().indefinitely();

      for (Map.Entry<String, String> entry : values.entrySet()) {
         getOrCreateMap(name)
               .put(entry.getKey(), UniItem.fromItem(entry.getValue()));
      }

      return bulkIndexingOperation;
   }

   public Uni<String> get(String name, String key) {
      CacheAccessRecord<String> cacheAccessRecord = metrics.recordCacheAccess(name);
      try {
         Uni<String> uni = getOrCreateMap(name).get(key);
         cacheAccessRecord.localHit(uni instanceof UniItem<String>);
         return uni.onItemOrFailure().invoke(cacheAccessRecord);
      } catch (RuntimeException e) {
         cacheAccessRecord.recordThrowable(e);
         throw e;
      }
   }

   public Stream<String> getKeys(String name) {
      Cache<String, ?> cache = getOrCreateMap(name);
      return cache.asMap()
            .keySet()
            .stream();
   }

   public Uni<Map<String, String>> getAll(String name, Collection<String> keys) {
      Map<String, Uni<String>> res = getOrCreateMap(name).getAll(keys);

      if (res.isEmpty()) return EMPTY_MAP_UNI;

      UniJoin.Builder<KeyValuePair<String, String>> builder = Uni.join().builder();
      for (Map.Entry<String, Uni<String>> entry : res.entrySet()) {
         String key = entry.getKey();
         builder = builder.add(get(name, key).map(v -> KeyValuePair.of(key, v)));
      }
      return builder.joinAll().andFailFast()
            .map(values -> values.stream()
                  .collect(Collectors.toMap(KeyValuePair::getKey, KeyValuePair::getValue)));
   }

   public Uni<Boolean> remove(String name, String key) {
      Uni<String> indexUni = indexingHandler.remove(name, key)
            .<String>map(___ -> null)
            // Make sure subsequent subscriptions don't update index again
            .memoize().indefinitely();
      // We put a null uni into the map if a value existed before. This way we cache the tombstone.
      Uni<String> prev = getOrCreateMap(name).asMap()
            .replace(key, indexUni);
      if (prev != null) {
         // TODO: technically only want to do this on caller subscribing
         indexUni.subscribe()
               .with(s -> replace(name, key, indexUni, null), t -> actualRemove(name, key, indexUni));
         // TODO: technically this says it removed something even if the Uni contained a null value prior
         return Uni.createFrom().item(Boolean.TRUE);
      }
      return Uni.createFrom().item(Boolean.FALSE);
   }

   public void onRemoveCache(@Observes RuleEvents.EagerRuleRemoved ev) {
      removeCache(ev.name());
   }

   public void onRemoveCache(@Observes RuleEvents.LazyRuleRemoved ev) {
      removeCache(ev.name());
   }

   private void removeCache(String ruleName) {
      if (maps.containsKey(ruleName)) {
         log.debug("Removing cache {}. Invalidating all entries", ruleName);
         var cache = maps.remove(ruleName);
         cache.invalidateAll();
         cache.cleanUp();
         return;
      }
      log.debug("Cache {} not existent, not removing");
   }

   private LoadingCache<String, Uni<String>> createLoadingCache(String ruleName) {
      if (!ruleManager.containsRuleByName(ruleName)) {
         throw new IllegalArgumentException("Rule " + ruleName + " not configured");
      }

      LoadingCache<String, Uni<String>> cache = Caffeine.newBuilder()
            // TODO: populate this with config
            .maximumWeight(1_000_000)
            .<String, Uni<String>>weigher((k, v) -> {
               // TODO: need to revisit this later
               int size = k.length() * 2 + 1;
               if (v instanceof UniItem<String> uniItem) {
                  var actualValue = uniItem.getItem();
                  size += actualValue == null ? 0 : actualValue.length() * 2 + 1;
                  if (size < 0) {
                     size = Integer.MAX_VALUE;
                  }
               }
               return size;
            })
            .build(new CacheLoader<>() {
               @Override
               public Uni<String> load(String key) {
                   // Make sure to use memoize, so that if multiple people subscribe to this it won't cause
                   // multiple DB lookups
                   Uni<String> dbUni;
                   Rule rule = ruleManager.getEagerOrLazyRuleByName(ruleName);
                   if (rule instanceof EagerRule) {
                       dbUni = databaseHandler.select(rule, key)
                               .onItem()
                               .ifNotNull()
                               .transformToUni(value -> denormalize(ruleName, value))
                               .memoize()
                               .indefinitely();
                   } else {
                       dbUni = databaseHandler.select(rule, key)
                               .memoize()
                               .indefinitely();
                   }
                   // This will replace the pending Uni from the DB with a UniItem so we can properly size the entry
                   // Note due to how lazy subscribe works the entry won't be present in the map  yet
                   dbUni.subscribe()
                           .with(result -> replace(ruleName, key, dbUni, result), t -> actualRemove(ruleName, key, dbUni));
                   return dbUni;
               }

               @Override
               public Map<? extends String, ? extends Uni<String>> loadAll(Set<? extends String> keys) {
                  if (keys.isEmpty()) return Collections.emptyMap();

                  // TODO: single select?
                  Map<String, Uni<String>> response = new HashMap<>();
                  for (String key : keys) {
                     response.put(key, load(key));
                  }
                  return response;
               }
            });
      metrics.registerRulesMetrics(ruleName, cache);
      return cache;
   }
}
