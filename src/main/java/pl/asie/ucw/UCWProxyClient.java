/*
 * Copyright (c) 2017 Adrian Siekierka
 *
 * This file is part of Unlimited Chisel Works.
 *
 * Unlimited Chisel Works is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Unlimited Chisel Works is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Unlimited Chisel Works.  If not, see <http://www.gnu.org/licenses/>.
 */

package pl.asie.ucw;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.IRetexturableModel;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ProgressManager;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

public class UCWProxyClient extends UCWProxyCommon {
	private JsonObject chiselCache;
	private Map<ResourceLocation, BufferedImage> imageMap = new HashMap<>();
	private static Map<BufferedImage, int[]> rgbMap = new IdentityHashMap<>();

	public static int[] getRGB(BufferedImage image) {
		if (!rgbMap.containsKey(image)) {
			rgbMap.put(image, image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
		}
		return rgbMap.get(image);
	}

	public BufferedImage getBufferedImage(ResourceLocation location) {
		if (!imageMap.containsKey(location)) {
			try {
				ResourceLocation pngLocation = new ResourceLocation(location.getResourceDomain(), String.format("%s/%s%s", new Object[]{"textures", location.getResourcePath(), ".png"}));
				IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(pngLocation);
				imageMap.put(location, TextureUtil.readBufferedImage(resource.getInputStream()));
			} catch (IOException e) {
				e.printStackTrace();
				imageMap.put(location, UCWMagic.missingNo);
			}
		}

		return imageMap.get(location);
	}

	@SubscribeEvent
	public void onModelRegistry(ModelRegistryEvent event) {
		chiselCache = null;
	}

	@SubscribeEvent
	public void onModelBake(ModelBakeEvent event) {
		imageMap.clear();
		rgbMap.clear();
	}

	private ModelResourceLocation createMRL(UCWObjectFactory factory, int j) {
		List<String> propertyNames = new ArrayList<>();
		for (IProperty property : factory.block.getBlockState().getProperties()) {
			propertyNames.add(property.getName());
		}
		IBlockState targetState = factory.block.getStateFromMeta(j);
		Collections.sort(propertyNames);
		String variant = "";
		for (String s : propertyNames) {
			if (variant.length() > 0) variant += ",";
			IProperty property = factory.block.getBlockState().getProperty(s);
			variant += s + "=" + property.getName(targetState.getValue(property));
		}

		return new ModelResourceLocation(factory.block.getRegistryName(), variant);
	}

	/* Has to run before Chisel's stitcher */
	@SubscribeEvent(priority = EventPriority.HIGH)
	@SuppressWarnings("unchecked")
	public void onTextureStitchPre(TextureStitchEvent.Pre event) {
		imageMap.clear();
		rgbMap.clear();

		// don't tell lex
		ModelLoader loader;
		Map<ModelResourceLocation, IModel> secretSauce = null;
		BlockModelShapes blockModelShapes = null;
		try {
			Class c = Class.forName("net.minecraftforge.client.model.ModelLoader$VanillaLoader");
			Field f = c.getDeclaredField("INSTANCE");
			f.setAccessible(true);
			Object o = f.get(null);
			f = c.getDeclaredField("loader");
			f.setAccessible(true);
			loader = (ModelLoader) f.get(o);
			f = ModelLoader.class.getDeclaredField("stateModels");
			f.setAccessible(true);
			secretSauce = (Map<ModelResourceLocation, IModel>) f.get(loader);
			f = ReflectionHelper.findField(ModelBakery.class, "blockModelShapes", "field_177610_k");
			f.setAccessible(true);
			blockModelShapes = (BlockModelShapes) f.get(loader);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		UnlimitedChiselWorks.proxy.progressPush("UCW: generating models", UnlimitedChiselWorks.BLOCK_RULES.size());
		int cc = 0;

		for (UCWBlockRule rule : UnlimitedChiselWorks.BLOCK_RULES) {
			UnlimitedChiselWorks.proxy.progressStep(String.format("%d%%", (++cc) * 100 / UnlimitedChiselWorks.BLOCK_RULES.size()));

			Map<IBlockState, ModelResourceLocation> fromVariants = blockModelShapes.getBlockStateMapper().getVariants(rule.fromBlock);
			Map<IBlockState, ModelResourceLocation> overlayVariants = blockModelShapes.getBlockStateMapper().getVariants(rule.overlayBlock);
			Map<IBlockState, ModelResourceLocation> throughVariants = blockModelShapes.getBlockStateMapper().getVariants(rule.throughBlock);
			Map<IBlockState, ModelResourceLocation> basedUponVariants = blockModelShapes.getBlockStateMapper().getVariants(rule.basedUponBlock);

			for (int i = 0; i < rule.from.size(); i++) {
				if (rule.from.get(i) != null) {
					IBlockState state = rule.from.get(i);
					String s2 = rule.fromBlock.getRegistryName().toString().trim().replaceAll("[^A-Za-z0-9]", "_") + "_" + i;

					IBlockState stateOverlay = rule.overlay.get(i);
					IModel modelFrom = secretSauce.get(fromVariants.get(state));
					IModel modelOverlay = secretSauce.get(overlayVariants.get(stateOverlay));
					IBlockState stateBasedUpon = rule.basedUpon.size() == 1 ? rule.basedUpon.get(0) : rule.basedUpon.get(i);
					IModel modelBasedUpon = secretSauce.get(basedUponVariants.get(stateBasedUpon));
					ResourceLocation textureFrom = UCWMagic.getLocation(state, fromVariants.get(state), modelFrom);
					ResourceLocation textureOverlay = UCWMagic.getLocation(stateOverlay, overlayVariants.get(stateOverlay), modelOverlay);
					ResourceLocation textureBasedUpon = UCWMagic.getLocation(stateBasedUpon, basedUponVariants.get(stateBasedUpon), modelBasedUpon);

					for (int j = 0; j < 16; j++) {
						IBlockState throughState = rule.through.get(j);
						if (throughState == null) continue;

						ModelResourceLocation throughLoc = throughVariants.get(throughState);
						IModel modelThrough = secretSauce.get(throughLoc);
						ImmutableMap.Builder<String, String> textureRemapMap = ImmutableMap.builder();
						for (ResourceLocation oldLocation : modelThrough.getTextures()) {
							ResourceLocation newLocation = new ResourceLocation("ucw_generated",
									"blocks/ucw_ucw_" + s2 + "/" + oldLocation.getResourceDomain() + "/" + oldLocation.getResourcePath().substring(7));

							textureRemapMap.put(oldLocation.toString(), newLocation.toString());
							event.getMap().setTextureEntry(new TextureAtlasSprite(newLocation.toString()) {
								@Override
								public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) {
									return true;
								}

								@Override
								public boolean load(IResourceManager manager, ResourceLocation location) {
									BufferedImage fromTex = getBufferedImage(textureFrom);
									BufferedImage overlayTex = getBufferedImage(textureOverlay);
									BufferedImage basedUponTex = getBufferedImage(textureBasedUpon);
									BufferedImage locationTex = getBufferedImage(oldLocation);

									setIconWidth(locationTex.getWidth());
									setIconHeight(locationTex.getHeight());

									clearFramesTextureData();
									int[][] pixels = new int[Minecraft.getMinecraft().gameSettings.mipmapLevels + 1][];
									pixels[0] = UCWMagic.transform(locationTex, fromTex, overlayTex, basedUponTex, rule.mode);
									framesTextureData.add(pixels);

									return false;
								}
							});
						}

						UCWObjectFactory factory = rule.objectFactories.get(i);
						ModelResourceLocation targetLoc = createMRL(factory, j);

						if (throughLoc.getResourceDomain().equals("chisel")) {
							// fun!
							try {
								if (chiselCache == null) {
									InputStream stream = Minecraft.getMinecraft().getResourceManager().getResource(
											new ResourceLocation("chisel", "blockstates/default.json")
									).getInputStream();
									InputStreamReader reader = new InputStreamReader(stream);

									chiselCache = UnlimitedChiselWorks.GSON.fromJson(reader, JsonElement.class).getAsJsonObject();

									reader.close();
									stream.close();
								}

								JsonObject variants = chiselCache.get("variants").getAsJsonObject();
								if (variants.has(throughLoc.getVariant())) {
									String modelPath = variants
											.get(throughLoc.getVariant()).getAsJsonObject().get("model").getAsString();
									modelPath = modelPath.replaceFirst("chisel:", "ucw_generated:ucw_ucw_" + s2 + "/chisel/");
									secretSauce.put(targetLoc, ModelLoaderRegistry.getModel(new ModelResourceLocation(modelPath)));
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
						} else if (modelThrough instanceof IRetexturableModel) {
							secretSauce.put(targetLoc, ((IRetexturableModel) modelThrough).retexture(textureRemapMap.build()));
						}
					}
				}
			}
		}

		UnlimitedChiselWorks.proxy.progressPop();
	}

	@Override
	public void preInit() {
		try {
			Field field = ReflectionHelper.findField(Minecraft.class, "defaultResourcePacks", "field_110449_ao");
			((List) field.get(Minecraft.getMinecraft())).add(UCWFakeResourcePack.INSTANCE);

			((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager()).registerReloadListener(UCWFakeResourcePack.INSTANCE);

			// TODO: Can we get rid of this to save a bit of loading time?
			// (We can, but it involves loading Minecraft.<init> a bit early.
			// Hmm.)
			Minecraft.getMinecraft().refreshResources();
		} catch (Exception e) {
			e.printStackTrace();
		}

		for (UCWBlockRule rule : UnlimitedChiselWorks.BLOCK_RULES) {
			for (int i = 0; i < rule.from.size(); i++) {
				if (rule.from.get(i) != null) {
					for (int j = 0; j < 16; j++) {
						IBlockState throughState = rule.through.get(j);
						if (throughState == null) continue;

						UCWObjectFactory factory = rule.objectFactories.get(i);
						ModelResourceLocation targetLoc = createMRL(factory, j);
						ModelLoader.setCustomModelResourceLocation(factory.item, j, targetLoc);
					}
				}
			}
		}
	}

	@Override
	public void init() {
		super.init();
		MinecraftForge.EVENT_BUS.register(this);
	}

	private final Deque<ProgressManager.ProgressBar> progressBarDeque = new ArrayDeque<>();

	@Override
	public void progressPush(String name, int count) {
		progressBarDeque.addFirst(ProgressManager.push(name, count));
	}

	@Override
	public void progressStep(String text) {
		progressBarDeque.peekFirst().step(text);
	}

	@Override
	public void progressPop() {
		ProgressManager.pop(progressBarDeque.removeFirst());
	}

	@Override
	public void getSubItemsUCW(IUCWItem item, List<ItemStack> list) {
		((Item) item).getSubItems((Item) item, CreativeTabs.SEARCH, list);
	}

	@Override
	public void initBlock(IBlockState state, Block block) {
		block.setCreativeTab(state.getBlock().getCreativeTabToDisplayOn());
	}
}
