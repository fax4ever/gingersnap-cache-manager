package io.gingersnapproject.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.infinispan.commons.dataconversion.internal.Json;
import org.junit.jupiter.api.Test;

import io.gingersnapproject.Caches;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(SearchTestResource.class)
public class CachesAndQueryTest {
   private static final String INDEX_NAME = "developers-3";
   @Inject
   Caches caches;

   @Inject
   QueryHandler queryHandler;

   @Test
   public void testPutAndRemove() throws InterruptedException {
      Json originalJohn = Json.object("surname", "Doo", "name", "John", "nick", "john");
      Json originalMike = Json.object("surname", "Lee", "name", "Mike", "nick", "mike");

      caches.put(INDEX_NAME, "john", originalJohn.toString()).await().indefinitely();
      caches.put(INDEX_NAME, "mike", originalMike.toString()).await().indefinitely();

      Json reloadedJohn = Json.read(caches.get(INDEX_NAME, "john").await().indefinitely());
      Json reloadedMike = Json.read(caches.get(INDEX_NAME, "mike").await().indefinitely());
      assertThat(originalJohn).isEqualTo(reloadedJohn);
      assertThat(originalMike).isEqualTo(reloadedMike);

      Thread.sleep(2000);

      QueryResult result = queryHandler.query("select * from " + INDEX_NAME + " order by name")
            .await().indefinitely();
      assertThat(result.hitCount()).isEqualTo(2L);
      assertThat(result.hitCountExact()).isTrue();
      assertThat(result.hitsExacts()).isTrue();

      List<String> hits = result.hits();
      assertThat(hits).containsExactly(originalJohn.toString(), originalMike.toString());

      caches.remove(INDEX_NAME, "john").await().indefinitely();

      assertThat(caches.get(INDEX_NAME, "john").await().indefinitely()).isNull();

      Thread.sleep(2000);

      result = queryHandler.query("select * from " + INDEX_NAME + " order by name")
            .await().indefinitely();
      assertThat(result.hitCount()).isEqualTo(1L);
      assertThat(result.hitCountExact()).isTrue();
      assertThat(result.hitsExacts()).isTrue();

      hits = result.hits();
      assertThat(hits).containsExactly(originalMike.toString());

      HashMap<String, String> values = new HashMap<>();
      for (int i = 0; i < 100; i++) {
         String key = StringUtils.leftPad(i + "", 3, "0");
         String surname = StringUtils.leftPad(i / 10 + "", 2, "0");
         String name = StringUtils.leftPad(i % 10 + "", 2, "0");

         values.put(key, Json.object("surname", surname, "name", name, "nick", key).toString());
      }

      caches.putAll(INDEX_NAME, values).await().indefinitely();

      result = queryHandler.query("select * from " + INDEX_NAME + " where surname = '07' order by name")
            .await().indefinitely();
      assertThat(result.hitCount()).isEqualTo(10L);
      assertThat(result.hitCountExact()).isTrue();
      assertThat(result.hitsExacts()).isTrue();

      hits = result.hits();
      assertThat(hits.get(3)).isEqualTo("{\"surname\":\"07\",\"name\":\"03\",\"nick\":\"073\"}");
   }
}