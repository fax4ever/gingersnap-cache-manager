package io.gingersnapproject.json;

import java.util.List;

import org.infinispan.commons.dataconversion.internal.Json;

import io.gingersnapproject.search.QueryResult;
import io.smallrye.mutiny.Uni;

public final class JsonHelper {

   private JsonHelper() {
   }

   public static Uni<String> json(Uni<QueryResult> queryResultUni) {
      return queryResultUni.onItem().transform(queryResult -> {
         // Json.make() seems to be not working:
         // > doing it manually
         Json result = Json.object();

         result.set("hitCount", queryResult.hitCount());
         result.set("hitCountExact", queryResult.hitCountExact());
         result.set("hits", JsonHelper.make(queryResult.hits()));
         result.set("hitsExacts", queryResult.hitsExacts());

         return result.toString();
      });
   }

   public static Json make(List<String> hits) {
      Json array = Json.array();
      for (String item : hits) {
         // take as it is, we don't need to parse / validate
         array.add(Json.factory().raw(item));
      }
      return array;
   }
}
