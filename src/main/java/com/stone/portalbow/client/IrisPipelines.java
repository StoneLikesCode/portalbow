package com.stone.portalbow.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Gives each portal view its own copy of the shader pack's machinery when Iris is installed.
 *
 * <p>A shader pack remembers things from one frame to the next (the previous picture, where the
 * camera was, its shadow maps) and assumes there is only ever one camera. If the portal view and
 * the normal view shared that memory, each would smear into the other. Iris keeps all of it in
 * one object per dimension, a "pipeline". So for each portal view we ask Iris to build a second
 * pipeline, slot it in while the portal view is drawn, and put the real one back afterwards.
 *
 * <p>Iris has no official way to do this, so we reach into its private parts by name
 * ("reflection"). THIS IS THE FRAGILE BIT: it was written against Iris 1.11.7 and an Iris update
 * may rename these things. If that happens {@link TrueViews} notices the failure, switches the
 * real view off and falls back to the simpler picture; the game keeps running.
 */
final class IrisPipelines {
	private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

	private static Method getPipelineManager;
	private static Method getCurrentDimension;
	private static Method preparePipeline;
	private static Method getVersion;
	private static Method destroy;
	private static Field pipelinesPerDimension;
	private static Field pipelineFactory;
	private static Class<?> shaderPipelineClass;

	/** One spare pipeline per portal being viewed. */
	private static final Map<Integer, Spare> SPARES = new HashMap<>();

	private static Object manager;
	private static Object dimension;
	private static Object realPipeline;

	private record Spare(Object pipeline, Object dimension, int version, long[] usedAt) {
	}

	private IrisPipelines() {
	}

	private static void lookUp() throws ReflectiveOperationException {
		if (getPipelineManager != null) {
			return;
		}
		Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
		Class<?> managerClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
		Class<?> pipelineClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
		Class<?> idClass = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
		shaderPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
		getCurrentDimension = iris.getMethod("getCurrentDimension");
		preparePipeline = managerClass.getMethod("preparePipeline", idClass);
		getVersion = managerClass.getMethod("getVersionCounterForSodiumShaderReload");
		destroy = pipelineClass.getMethod("destroy");
		pipelinesPerDimension = managerClass.getDeclaredField("pipelinesPerDimension");
		pipelinesPerDimension.setAccessible(true);
		pipelineFactory = managerClass.getDeclaredField("pipelineFactory");
		pipelineFactory.setAccessible(true);
		getPipelineManager = iris.getMethod("getPipelineManager");
	}

	/** Slots in the spare pipeline for this portal. Always follow with {@link #end()}. */
	@SuppressWarnings("unchecked")
	static void begin(int portalId) throws ReflectiveOperationException {
		if (!IRIS_LOADED) {
			return;
		}
		lookUp();
		Object pipelineManager = getPipelineManager.invoke(null);
		Object currentDimension = getCurrentDimension.invoke(null);
		Map<Object, Object> pipelines = (Map<Object, Object>) pipelinesPerDimension.get(pipelineManager);
		Object real = pipelines.get(currentDimension);
		if (!shaderPipelineClass.isInstance(real)) {
			return; // Shaders are switched off (or not set up yet): nothing needs separating.
		}

		int version = (int) getVersion.invoke(pipelineManager);
		Spare spare = SPARES.get(portalId);
		if (spare != null && (spare.version != version || !spare.dimension.equals(currentDimension))) {
			// The shader pack was reloaded or we changed dimension: this spare is out of date.
			destroy.invoke(spare.pipeline);
			SPARES.remove(portalId);
			spare = null;
		}
		if (spare == null) {
			Object built = ((Function<Object, Object>) pipelineFactory.get(pipelineManager)).apply(currentDimension);
			if (!shaderPipelineClass.isInstance(built)) {
				throw new IllegalStateException("Iris could not build a second shader pipeline");
			}
			spare = new Spare(built, currentDimension, version, new long[1]);
			SPARES.put(portalId, spare);
		}
		spare.usedAt[0] = System.nanoTime();

		pipelines.put(currentDimension, spare.pipeline);
		preparePipeline.invoke(pipelineManager, currentDimension);
		manager = pipelineManager;
		dimension = currentDimension;
		realPipeline = real;
	}

	/** Puts the real pipeline back. Safe to call even if {@link #begin} did nothing. */
	@SuppressWarnings("unchecked")
	static void end() {
		if (manager == null) {
			return;
		}
		try {
			((Map<Object, Object>) pipelinesPerDimension.get(manager)).put(dimension, realPipeline);
			preparePipeline.invoke(manager, dimension);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not hand the shader pipeline back to Iris", e);
		} finally {
			manager = null;
			dimension = null;
			realPipeline = null;
		}
	}

	/** Frees spare pipelines (they use a lot of video memory) for portals nobody has looked into lately. */
	static void freeUnused() {
		long now = System.nanoTime();
		for (Iterator<Spare> it = SPARES.values().iterator(); it.hasNext();) {
			Spare spare = it.next();
			if (now - spare.usedAt[0] > 20_000_000_000L) {
				try {
					destroy.invoke(spare.pipeline);
				} catch (ReflectiveOperationException ignored) {
					// Nothing useful to do; drop our reference either way.
				}
				it.remove();
			}
		}
	}
}
