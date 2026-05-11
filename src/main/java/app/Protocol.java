package app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

public final class Protocol {
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Request {
		public Request() {}
		public String jsonrpc;
		public JsonNode id;
		public String method;
		public JsonNode params;
	}

	public static final class Response {
		public String jsonrpc = "2.0";
		public JsonNode id;
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public JsonNode result;
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public ResponseError error;

		public static Response ok(JsonNode id, JsonNode result) {
			Response resp = new Response();
			resp.id = id;
			resp.result = result;
			return resp;
		}


		public static Response err(JsonNode id, int code, String message) {
			Response resp = new Response();
			resp.id = id;
			resp.error = new ResponseError(code, message);
			return resp;
		}

		public static Response err(JsonNode id, int code, String message, JsonNode data) {
			Response resp = new Response();
			resp.id = id;
			resp.error = new ResponseError(code, message, data);
			return resp;
		}
	}

	public static final class ResponseError {
		public int code;
		public String message;
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public JsonNode data;
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public JsonNode _meta;

		public ResponseError(int code, String message) {
			this.code = code;
			this.message = message;
		}

		public ResponseError(int code, String message, JsonNode data) {
			this.code = code;
			this.message = message;
			this.data = data;
		}
	}

	private Protocol() {}
}
