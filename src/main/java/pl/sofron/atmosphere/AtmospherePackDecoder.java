package pl.sofron.atmosphere;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

final class AtmospherePackDecoder {
    record Modifier(List<String> targets, Integer fogColor, Integer skyColor,
                    String fogStart, String fogEnd) {}

    private AtmospherePackDecoder() {}

    static Modifier decode(JsonObject data, String defaultTarget) {
        List<String> targets = new ArrayList<>();
        if (data.has("targets") && data.get("targets").isJsonArray()) {
            JsonArray defined = data.getAsJsonArray("targets");
            for (JsonElement target : defined) {
                String id;
                if (target.isJsonPrimitive()) id = target.getAsString();
                else if (target.isJsonObject() && target.getAsJsonObject().has("id"))
                    id = target.getAsJsonObject().get("id").getAsString();
                else continue;
                if (id.indexOf(':') > 0 && !id.startsWith("#")) targets.add(id);
            }
        } else {
            targets.add(defaultTarget);
        }
        Integer fog = data.has("fog_color") && data.get("fog_color").isJsonPrimitive()
                ? data.get("fog_color").getAsInt() : null;
        Integer sky = data.has("sky_color") && data.get("sky_color").isJsonPrimitive()
                ? data.get("sky_color").getAsInt() : null;
        String start = data.has("fog_start") ? data.get("fog_start").getAsString() : null;
        String end = data.has("fog_end") ? data.get("fog_end").getAsString() : null;
        return new Modifier(List.copyOf(targets), fog, sky, start, end);
    }
}
