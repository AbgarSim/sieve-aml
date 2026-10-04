package dev.sieve.cli.command;

import dev.sieve.cli.CliContext;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ftm.FtmWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to export loaded entities, either as a JSON summary or in the FollowTheMoney entity
 * format (one JSON object per line) for tools that read it.
 */
@Command(name = "export", mixinStandardHelpOptions = true, description = "Export loaded entities")
public class ExportCommand implements Runnable {

    @Option(
            names = {"--format", "-f"},
            description = "Output format: json, or ftm for FollowTheMoney JSON lines",
            defaultValue = "json")
    private String format;

    @Override
    public void run() {
        CliContext ctx = CliContext.instance();
        EntityIndex index = ctx.entityIndex();

        if (index.size() == 0) {
            System.err.println("Index is empty. Run 'sieve fetch' first.");
            return;
        }

        if ("ftm".equalsIgnoreCase(format)) {
            exportFtm(index.all());
            return;
        }
        if (!"json".equalsIgnoreCase(format)) {
            System.err.printf("Unsupported format: %s. Use 'json' or 'ftm'.%n", format);
            return;
        }

        Collection<SanctionedEntity> entities = index.all();
        System.out.println("[");
        int count = 0;
        for (SanctionedEntity entity : entities) {
            count++;
            System.out.printf("  {%n");
            System.out.printf("    \"id\": \"%s\",%n", escapeJson(entity.id()));
            System.out.printf("    \"entityType\": \"%s\",%n", entity.entityType().name());
            System.out.printf("    \"listSource\": \"%s\",%n", entity.listSource().name());
            System.out.printf(
                    "    \"primaryName\": \"%s\",%n", escapeJson(entity.primaryName().fullName()));
            System.out.printf("    \"aliases\": [");
            for (int i = 0; i < entity.aliases().size(); i++) {
                if (i > 0) {
                    System.out.print(", ");
                }
                System.out.printf("\"%s\"", escapeJson(entity.aliases().get(i).fullName()));
            }
            System.out.println("],");
            System.out.printf(
                    "    \"topics\": [%s],%n",
                    String.join(
                            ", ",
                            entity.topics().stream().map(t -> "\"" + t.name() + "\"").toList()));
            System.out.printf(
                    "    \"programs\": [%s]%n",
                    String.join(
                            ", ",
                            entity.programs().stream()
                                    .map(p -> "\"" + escapeJson(p.code()) + "\"")
                                    .toList()));
            System.out.printf("  }%s%n", count < entities.size() ? "," : "");
        }
        System.out.println("]");

        System.err.printf("Exported %d entities in %s format.%n", entities.size(), format);
    }

    private static void exportFtm(Collection<SanctionedEntity> entities) {
        try {
            long objects = new FtmWriter().write(entities, System.out);
            System.err.printf(
                    "Exported %d entities as %d FollowTheMoney objects.%n",
                    entities.size(), objects);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
