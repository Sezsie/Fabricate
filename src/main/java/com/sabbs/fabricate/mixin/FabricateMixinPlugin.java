package com.sabbs.fabricate.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin that conditionally disables our EMI-specific mixins when
 * EMI isn't on the classpath. Without this, {@code EmiRecipeFillerMixin} would
 * fail to apply (its target class doesn't exist), and mixin throws a fatal
 * error.
 *
 * <p>We detect EMI via {@link Class#forName} instead of Forge's {@code ModList}
 * because mixin application runs during the {@code cpw.mods.modlauncher}
 * transformation phase, before {@code ModList} is populated.
 */
public final class FabricateMixinPlugin implements IMixinConfigPlugin {

    private static final boolean EMI_PRESENT = isClassPresent("dev.emi.emi.api.EmiPlugin");

    private static final Set<String> EMI_GATED_MIXINS = Set.of(
        "com.sabbs.fabricate.mixin.EmiRecipeFillerMixin",
        "com.sabbs.fabricate.mixin.EmiScreenManagerMixin"
    );

    private static boolean isClassPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, FabricateMixinPlugin.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, org.objectweb.asm.tree.ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, org.objectweb.asm.tree.ClassNode c, String m, IMixinInfo i) {}
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (EMI_GATED_MIXINS.contains(mixinClassName)) return EMI_PRESENT;
        return true;
    }
}
