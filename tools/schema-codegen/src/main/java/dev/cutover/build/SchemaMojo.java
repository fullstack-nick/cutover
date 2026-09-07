package dev.cutover.build;

import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Generates owner-local SQL types from the actual migrations, never from a live database. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class SchemaMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/jooq", required = true)
    private File output;

    @Parameter(required = true)
    private String postgresImage;

    @Override public void execute() throws MojoExecutionException {
        File owner = new File(project.getBasedir(), "src/main/resources/db/" + project.getArtifactId());
        File technical = new File(project.getBasedir(), "../../platform/service-starter/src/main/resources/db/platform");
        if (!owner.isDirectory() || !technical.isDirectory()) throw new MojoExecutionException("Owner migration directories are missing.");
        try (var postgres = new PostgreSQLContainer(postgresImage)
                .withLabel("dev.cutover.project", "cutover").withLabel("dev.cutover.purpose", "schema-codegen")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + technical.getCanonicalPath(), "filesystem:" + owner.getCanonicalPath()).load().migrate();
            GenerationTool.generate(new Configuration().withLogging(Logging.WARN)
                    .withJdbc(new Jdbc().withDriver("org.postgresql.Driver").withUrl(postgres.getJdbcUrl())
                            .withUser(postgres.getUsername()).withPassword(postgres.getPassword()))
                    .withGenerator(new Generator().withDatabase(new Database().withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public").withExcludes("flyway_schema_history"))
                            .withGenerate(new Generate().withGeneratedAnnotation(false).withPojos(false).withDaos(false))
                            .withTarget(new Target().withPackageName("dev.cutover.generated." + project.getArtifactId().replace('-', '_'))
                                    .withDirectory(output.getCanonicalPath()))));
            project.addCompileSourceRoot(output.getCanonicalPath());
            getLog().info("Generated " + project.getArtifactId() + " types from migrated disposable PostgreSQL; runtime databases were not used.");
        } catch (Exception error) {
            throw new MojoExecutionException("Disposable schema generation failed for " + project.getArtifactId(), error);
        }
    }
}
