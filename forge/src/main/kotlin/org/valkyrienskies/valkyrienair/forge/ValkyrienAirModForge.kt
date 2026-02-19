package org.valkyrienskies.valkyrienair.forge

import dev.architectury.platform.forge.EventBuses
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.valkyrienair.ValkyrienAirMod
import org.valkyrienskies.valkyrienair.forge.client.ValkyrienAirModForgeClient

@Mod(ValkyrienAirMod.MOD_ID)
class ValkyrienAirModForge {

    //Deferred Registries
    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ValkyrienAirMod.MOD_ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ValkyrienAirMod.MOD_ID)
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ValkyrienAirMod.MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ValkyrienAirMod.MOD_ID)

    // Put RegistryObjects here:

    // end of RegistryObjects

    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(ValkyrienAirMod.MOD_ID, modEventBus)

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ValkyrienAirForgeConfig.spec)
        ValkyrienAirForgeConfig.applyToCommonConfig()

        modEventBus.addListener(::init)
        modEventBus.addListener(this::onConfigLoading)
        modEventBus.addListener(this::onConfigReloading)
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(ValkyrienAirModForgeClient.Companion::clientInit)
        }

        // Run our common setup.
        ValkyrienAirMod.init()
    }

    // Helper function, taken from VS2.
    private fun registerBlockAndItem(registryName: String, blockSupplier: () -> Block): RegistryObject<Block> {
        val blockRegistry = BLOCKS.register(registryName, blockSupplier)
        ITEMS.register(registryName) { BlockItem(blockRegistry.get(), Item.Properties()) }
        return blockRegistry
    }

    private fun onConfigLoading(event: ModConfigEvent.Loading) {
        ValkyrienAirForgeConfig.onConfigLoading(event)
    }

    private fun onConfigReloading(event: ModConfigEvent.Reloading) {
        ValkyrienAirForgeConfig.onConfigReloading(event)
    }

    companion object {
        @JvmStatic
        fun init (event: FMLCommonSetupEvent) {
            // Put anything initialized on forge-side here.
        }
    }
}
