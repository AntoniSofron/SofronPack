package pl.sofron.atmosphere;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

@SuppressWarnings("deprecation")
public final class AtmosphereResourceLoader {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("sofronpack", "atmosphere");

    private AtmosphereResourceLoader() {}

    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return ID;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        AtmospherePresets.loadFromPacks(manager);
                    }
                });
    }
}
