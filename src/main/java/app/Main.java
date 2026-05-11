package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public final class Main {
	public static void main(String[] args) throws Exception {
		if (args.length > 0) {
			handleArgs(args);
			return;
		}
		McpServer server = new McpServer();
		server.run();
	}

	private static void handleArgs(String[] args) throws IOException {
		if ("--print-config-schema".equals(args[0])) {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode schema = ConfigSchema.build(mapper);
			System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
			return;
		}
		System.err.println("Unknown argument: " + args[0]);
	}
}
