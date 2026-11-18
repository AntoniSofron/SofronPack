package pl.sofron.atmosphere;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AtmospherePackDecoderTest {
    @Test
    void directBiomePreset() {
        var rule = AtmospherePackDecoder.decode(JsonParser.parseString(
                "{\"fog_color\": 11056276, \"sky_color\": 9149554}").getAsJsonObject(), "minecraft:swamp");
        assertEquals(List.of("minecraft:swamp"), rule.targets());
        assertEquals(11056276, rule.fogColor());
        assertEquals(9149554, rule.skyColor());
        assertNull(rule.fogStart());
    }

    @Test
    void groupTargetsAndExpressions() {
        var rule = AtmospherePackDecoder.decode(JsonParser.parseString(
                "{\"targets\": [\"minecraft:swamp\", {\"id\": \"nomansland:bog\", \"required\": false}],"
                + "\"fog_end\": \"0.6 - max(0, RAIN * 0.3)\", \"fog_start\": 10.65}"
                ).getAsJsonObject(), "ambient_fog:swamp");
        assertEquals(List.of("minecraft:swamp", "nomansland:bog"), rule.targets());
        assertEquals("0.6 - max(0, RAIN * 0.3)", rule.fogEnd());
        assertEquals("10.65", rule.fogStart());
    }
}
