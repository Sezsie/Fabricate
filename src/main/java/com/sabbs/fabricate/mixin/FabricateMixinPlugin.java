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
 * <p>We detect EMI by asking the classloader for the EmiPlugin <em>class
 * resource</em> rather than loading the class itself. {@code Class.forName}
 * (even with {@code initialize=false}) resolves the class and triggers the
 * classloader to define it, which is too aggressive here: EMI ships its own
 * {@code GlobalMixin} targeting {@code dev.emi.emi.api.EmiPlugin}, and if
 * we define that class before Mixin's transformer reaches it, EMI's mixin
 * load aborts the JVM with {@code MixinTargetAlreadyLoadedException}.
 * {@link ClassLoader#getResource} checks the same classpath without ever
 * involving class definition, so it's safe to call from a mixin plugin's
 * static init.
 *
 * <p>Forge's {@code ModList} isn't usable here either since mixin application
 * runs during the {@code cpw.mods.modlauncher} transformation phase, before
 * {@code ModList} is populated.
 */
public final class FabricateMixinPlugin implements IMixinConfigPlugin {

    private static final boolean EMI_PRESENT = isClassResourcePresent("dev/emi/emi/api/EmiPlugin.class");

    private static final Set<String> EMI_GATED_MIXINS = Set.of(
        "com.sabbs.fabricate.mixin.EmiRecipeFillerMixin",
        "com.sabbs.fabricate.mixin.EmiScreenManagerMixin"
    );

    /**
     * Returns true when the bytecode resource for {@code path} (a class
     * resource path, e.g. {@code "dev/emi/emi/api/EmiPlugin.class"}) is
     * discoverable on this classloader. Doesn't define or initialize the
     * class - safe to call from a mixin config plugin's static init.
     */
    private static boolean isClassResourcePresent(String path) {
        try {
            return FabricateMixinPlugin.class.getClassLoader().getResource(path) != null;
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
