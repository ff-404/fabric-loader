/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.game;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlConversionException;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class GameProviderHelper {
	private GameProviderHelper() { }

	public static Path getCommonGameJar() {
		return getGameJar(SystemProperties.GAME_JAR_PATH);
	}

	public static Path getEnvGameJar(EnvType env) {
		return getGameJar(env == EnvType.CLIENT ? SystemProperties.GAME_JAR_PATH_CLIENT : SystemProperties.GAME_JAR_PATH_SERVER);
	}

	private static Path getGameJar(String property) {
		String val = System.getProperty(property);
		if (val == null) return null;

		Path path = Paths.get(val);
		if (!Files.exists(path)) throw new RuntimeException("Game jar "+path+" ("+LoaderUtil.normalizePath(path)+") configured through "+property+" system property doesn't exist");

		return LoaderUtil.normalizeExistingPath(path);
	}

	public static @Nullable List<Path> getLibraries(String property) {
		String value = System.getProperty(property);
		if (value == null) return null;

		List<Path> ret = new ArrayList<>();

		for (String pathStr : value.split(File.pathSeparator)) {
			if (pathStr.isEmpty()) continue;

			if (pathStr.startsWith("@")) {
				Path path = Paths.get(pathStr.substring(1));

				if (!Files.isRegularFile(path)) {
					Log.warn(LogCategory.GAME_PROVIDER, "Skipping missing/invalid library list file %s", path);
					continue;
				}

				try (BufferedReader reader = Files.newBufferedReader(path)) {
					String line;

					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.isEmpty()) continue;

						addLibrary(line, ret);
					}
				} catch (IOException e) {
					throw new RuntimeException(String.format("Error reading library list file %s", path), e);
				}
			} else {
				addLibrary(pathStr, ret);
			}
		}

		return ret;
	}

	public static void addLibrary(String pathStr, List<Path> out) {
		Path path = LoaderUtil.normalizePath(Paths.get(pathStr));

		if (!Files.exists(path)) { // missing
			Log.warn(LogCategory.GAME_PROVIDER, "Skipping missing library path %s", path);
		} else {
			out.add(path);
		}
	}

	public static Optional<Path> getSource(ClassLoader loader, String filename) {
		URL url;

		if ((url = loader.getResource(filename)) != null) {
			try {
				return Optional.of(UrlUtil.getCodeSource(url, filename));
			} catch (UrlConversionException e) {
				// TODO: Point to a logger
				e.printStackTrace();
			}
		}

		return Optional.empty();
	}

	public static List<Path> getSources(ClassLoader loader, String filename) {
		try {
			Enumeration<URL> urls = loader.getResources(filename);
			List<Path> paths = new ArrayList<>();

			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();

				try {
					paths.add(UrlUtil.getCodeSource(url, filename));
				} catch (UrlConversionException e) {
					// TODO: Point to a logger
					e.printStackTrace();
				}
			}

			return paths;
		} catch (IOException e) {
			e.printStackTrace();
			return Collections.emptyList();
		}
	}

	public static FindResult findFirst(List<Path> paths, Map<Path, ZipFile> zipFiles, boolean isClassName, String... names) {
		for (String name : names) {
			String file = isClassName ? LoaderUtil.getClassFileName(name) : name;

			for (Path path : paths) {
				if (Files.isDirectory(path)) {
					if (Files.exists(path.resolve(file))) {
						return new FindResult(name, path);
					}
				} else {
					ZipFile zipFile = zipFiles.get(path);

					if (zipFile == null) {
						try {
							zipFile = new ZipFile(path.toFile());
							zipFiles.put(path, zipFile);
						} catch (IOException e) {
							throw new RuntimeException("Error reading "+path, e);
						}
					}

					if (zipFile.getEntry(file) != null) {
						return new FindResult(name, path);
					}
				}
			}
		}

		return null;
	}

	public static final class FindResult {
		public final String name;
		public final Path path;

		FindResult(String name, Path path) {
			this.name = name;
			this.path = path;
		}
	}
}
