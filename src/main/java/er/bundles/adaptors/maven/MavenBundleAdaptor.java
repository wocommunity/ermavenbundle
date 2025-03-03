package er.bundles.adaptors.maven;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import com.webobjects.foundation.NSForwardException;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSNestedProperties;
import com.webobjects.foundation.development.NSBundleAdaptorProvider;
import com.webobjects.foundation.development.NSBundleInfo;
import com.webobjects.foundation.development.NSBundleInfo.CFBundlePackageType;

public class MavenBundleAdaptor implements NSBundleAdaptorProvider {
	private static final List<String> BUNDLE_PACKAGING = Arrays.asList("woapplication", "woframework");

	@Override
	public String adaptorBundlePath(final FileSystem fs, final String bundlePath) {
		return locateAdaptorBundlePath(fs, bundlePath).get();
	}

	@Override
	public NSBundleInfo bundleInfoFromFileSystem(final FileSystem fs, final Path fsBundlePath) {
		final NSMutableDictionary<String, Object> dictionary = new NSMutableDictionary<>();
		final Optional<Properties> buildProps = Optional.of(fsBundlePath.resolve("build.properties"))
				.filter(Files::exists).filter(Files::isRegularFile).filter(Files::isReadable).map(p -> {
					final Properties props = new Properties();
					try (FileReader reader = new FileReader(p.toFile())) {
						props.load(reader);
					} catch (final IOException e) {
						throw NSForwardException._runtimeExceptionForThrowable(e);
					}
					return props;
				});
		buildProps.map(p -> p.getProperty("principalClass")).ifPresent(
				principalClass -> dictionary.setObjectForKey(principalClass, NSBundleInfo.NS_PRINCIPAL_CLASS_KEY));
		buildProps.map(p -> p.getProperty("eoAdaptorClassName")).filter(name -> !name.isEmpty())
				.ifPresent(eoAdaptorClassName -> dictionary.setObjectForKey(eoAdaptorClassName,
						NSBundleInfo.EO_ADAPTOR_CLASS_NAME_KEY));
		dictionary.setObjectForKey("webo", NSBundleInfo.CF_BUNDLE_SIGNATURE_KEY);

		final Path pom = fsBundlePath.resolve("pom.xml");
		try (FileReader reader = new FileReader(pom.toFile())) {
			final Model model = new MavenXpp3Reader().read(reader);
			final boolean isApp = "woapplication".equals(model.getPackaging());
			final CFBundlePackageType type = isApp ? CFBundlePackageType.APPL : CFBundlePackageType.FMWK;
			dictionary.setObjectForKey(type.name(), NSBundleInfo.CF_BUNDLE_PACKAGE_TYPE_KEY);
			dictionary.setObjectForKey(model.getArtifactId(), NSBundleInfo.NS_EXECUTABLE_KEY);
			dictionary.setObjectForKey(Optional.ofNullable(model.getVersion()).orElse(model.getParent().getVersion()),
					NSBundleInfo.CF_BUNDLE_SHORT_VERSION_STRING_KEY);
		} catch (IOException | XmlPullParserException e) {
			// Error reading pom, return false
			throw NSForwardException._runtimeExceptionForThrowable(e);
		}

		try (Stream<Path> stream = Files.walk(fsBundlePath)) {
			final Boolean hasComponents = stream.filter(Files::isDirectory).filter(p -> p.toString().endsWith(".wo"))
					.findAny().isPresent();
			dictionary.setObjectForKey(hasComponents, NSBundleInfo.HAS_WOCOMPONENTS_KEY);
		} catch (final IOException e) {
			throw NSForwardException._runtimeExceptionForThrowable(e);
		}
		dictionary.setObjectForKey("0", NSBundleInfo.MANIFEST_IMPLEMENTATION_VERSION_KEY);
		return NSBundleInfo.forDictionary(dictionary);
	}

	@Override
	public Stream<String> classNamesForFileSystem(final FileSystem fs, final Path fsBundlePath) {
		final Path walkPath = fsBundlePath.resolve("target/classes/");
		try {
			return Files.walk(walkPath).filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".class")).map(Path::toString)
					.map(name -> name.substring(walkPath.toString().length() + 1, name.length() - ".class".length()))
					.map(name -> name.replace('/', '.')).map(String::intern);
		} catch (final IOException e) {
			throw NSForwardException._runtimeExceptionForThrowable(e);
		}
	}

	@Override
	public Path fsBundlePath(final FileSystem fs, final String adaptorBundlePath) {
		return fs.getPath(adaptorBundlePath);
	}

	@Override
	public boolean isAdaptable(final FileSystem fs, final String bundlePath) {
		return locateAdaptorBundlePath(fs, bundlePath).isPresent();
	}

	private Optional<String> locateAdaptorBundlePath(final FileSystem fs, final String bundlePath) {
		Path currentPath = fs.getPath(bundlePath);
		while (currentPath != null && Files.isDirectory(currentPath)) {
			final Path pom = currentPath.resolve("pom.xml");
			if (Files.exists(pom)) {
				try (FileReader reader = new FileReader(pom.toFile())) {
					final Model model = new MavenXpp3Reader().read(reader);
					return BUNDLE_PACKAGING.contains(model.getPackaging()) ? Optional.of(currentPath.toString())
							: Optional.empty();
				} catch (IOException | XmlPullParserException e) {
					// Error reading pom, return empty
					return Optional.empty();
				}
			}
			currentPath = currentPath.getParent();
		}
		return Optional.empty();
	}

	@Override
	public Properties propertiesForFileSystem(final FileSystem fs, final Path fsBundlePath) {
		// TODO Auto-generated method stub
		final List<Path> resPaths = resourcePathsForFileSystem(fs, fsBundlePath);
		return new NSNestedProperties(null);
	}

	@Override
	public List<Path> resourcePathsForFileSystem(final FileSystem fs, final Path fsBundlePath) {
		final Path pom = fsBundlePath.resolve("pom.xml");
		final List<String> paths = resourcePathsFromPom(pom);
		return paths.stream().map(fsBundlePath::resolve).collect(Collectors.toList());
	}

	private List<String> resourcePathsFromPom(final Path pom) {
		try (final FileReader reader = new FileReader(pom.toFile())) {
			final Model model = new MavenXpp3Reader().read(reader);
			final List<Resource> resourcePaths = Optional.ofNullable(model.getBuild()).map(Build::getResources)
					.orElse(Collections.emptyList());
			final List<String> paths = resourcePaths.stream().map(Resource::getDirectory).collect(Collectors.toList());
			final List<String> parentPaths = Optional.ofNullable(model.getParent()).map(Parent::getRelativePath)
					.map(pom::resolveSibling).map(this::resourcePathsFromPom).orElse(Collections.emptyList());
			paths.addAll(parentPaths);
			return paths;
		} catch (final IOException | XmlPullParserException e) {
			throw NSForwardException._runtimeExceptionForThrowable(e);
		}
	}
}
