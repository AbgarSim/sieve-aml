package dev.sieve.cli.command;

import dev.sieve.cli.CliContext;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.SanctionedEntity;
import java.util.Collection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to export loaded entities in JSON format.
 */
@Command(
        name = "export",
        mixinStandardHelpOptions = true,
        description = "Export loaded entities")
public class ExportCommand implements Runnable {

    @Option(
            names = {"--format", "-f"},
            description = "Output format (json)",
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

        if (!"json".equalsIgnoreCase(format)) {
            System.err.printf("Unsupported format: %s. Only 'json' is currently supported.%n", format);
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
                    "    \"primaryName\": \"%s\",%n",
                    escapeJson(entity.primaryName().fullName()));
            System.out.printf("    \"aliases\": [");
            for (int i = 0; i < entity.aliases().size(); i++) {
                if (i > 0) {
                    System.out.print(", ");
                }
                System.out.printf("\"%s\"", escapeJson(entity.aliases().get(i).fullName()));
            }
            System.out.println("],");
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

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
