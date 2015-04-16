package org.jboss.modules;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.jboss.modules.filter.PathFilters;

/**
 * @author Bob McWhirter
 * @author Ken Finnigan
 */
public class BootModuleFinder implements ModuleFinder {

    JarFile jarFile;

    public BootModuleFinder() throws IOException {
        this.jarFile = Util.rootJar();
    }

    @Override
    public ModuleSpec findModule(ModuleIdentifier identifier, ModuleLoader delegateLoader) throws ModuleLoadException {
        if (identifier.getName().equals("APP")) {
            try {
                return findAppModule();
            } catch (IOException e) {
                e.printStackTrace();
                throw new ModuleLoadException(e);
            }
        }

        String namePath = identifier.getName().replace('.', '/');
        String basePath = "modules/system/layers/base/" + namePath + "/" + identifier.getSlot();
        JarEntry moduleXmlEntry = jarFile.getJarEntry(basePath + "/module.xml");
        if (moduleXmlEntry == null) {
            return null;
        }
        ModuleSpec moduleSpec;
        try {
            InputStream inputStream = jarFile.getInputStream(moduleXmlEntry);
            try {
                moduleSpec = ModuleXmlParser.parseModuleXml(new ModuleXmlParser.ResourceRootFactory() {
                    public ResourceLoader createResourceLoader(final String rootPath, final String loaderPath, final String loaderName) throws IOException {
                        return new JarFileResourceLoader(loaderName, jarFile, rootPath + "/" + loaderPath);
                    }
                }, basePath, inputStream, moduleXmlEntry.getName(), delegateLoader, identifier);
            } finally {
                StreamUtil.safeClose(inputStream);
            }
        } catch (IOException e) {
            throw new ModuleLoadException("Failed to read module.xml file", e);
        }
        return moduleSpec;
    }

    private ModuleSpec findAppModule() throws IOException {

        Manifest manifest = Util.rootJar().getManifest();
        String artifactName = manifest.getMainAttributes().getValue("Application-Artifact");

        InputStream in = this.getClass().getClassLoader().getResourceAsStream("app/" + artifactName);

        ModuleSpec.Builder builder = ModuleSpec.build(ModuleIdentifier.create("APP"));

        if (in == null) {
            return null;
        }

        try {
            File tmp = File.createTempFile("app", ".jar");

            FileOutputStream out = new FileOutputStream(tmp);

            try {
                byte[] buf = new byte[1024];
                int len = -1;

                while ((len = in.read(buf)) >= 0) {
                    out.write(buf, 0, len);
                }
            } finally {
                out.close();
            }

            if (artifactName.endsWith(".war")) {
                ResourceLoader resourceLoader = new JarFileResourceLoader("app.jar", new JarFile(tmp), "WEB-INF/classes");
                ResourceLoaderSpec resourceLoaderSpec = ResourceLoaderSpec.createResourceLoaderSpec(resourceLoader);
                builder.addResourceRoot(resourceLoaderSpec);
            }

            ResourceLoader resourceLoader = new JarFileResourceLoader("app.jar", new JarFile(tmp));
            ResourceLoaderSpec resourceLoaderSpec = ResourceLoaderSpec.createResourceLoaderSpec(resourceLoader);
            builder.addResourceRoot(resourceLoaderSpec);

            System.setProperty( "wildfly.swarm.app.path", tmp.getAbsolutePath() );
            System.setProperty( "wildfly.swarm.app.name", artifactName );

        } finally {
            in.close();
        }


        builder.addDependency(DependencySpec.createLocalDependencySpec());
        builder.addDependency(DependencySpec.createModuleDependencySpec(ModuleIdentifier.create("org.wildfly.swarm.container"), false));

        String modulesStr = manifest.getMainAttributes().getValue("Feature-Pack-Modules");
        String[] modules = modulesStr.split(",");
        for ( int i = 0 ; i < modules.length ; ++i ) {
            String[] parts = modules[i].trim().split( ":" );
            builder.addDependency(DependencySpec.createModuleDependencySpec(
                    PathFilters.acceptAll(),
                    PathFilters.getMetaInfServicesFilter(),
                    null,
                    ModuleIdentifier.create( parts[0], parts[1] ),
                    false));
        }

        JarFile rootJar = Util.rootJar();
        ZipEntry depsTxt = rootJar.getEntry("dependencies.txt");

        if ( depsTxt != null ) {
            BufferedReader depsIn = new BufferedReader( new InputStreamReader( rootJar.getInputStream( depsTxt ) ) );

            String line = null;

            while ( ( line = depsIn.readLine() ) != null ) {
                ResourceLoader loader = ArtifactLoaderFactory.INSTANCE.getLoader(line.trim());
                builder.addResourceRoot( ResourceLoaderSpec.createResourceLoaderSpec( loader ) );
            }
        }

        ModuleSpec moduleSpec = builder.create();

        return moduleSpec;
    }
}
