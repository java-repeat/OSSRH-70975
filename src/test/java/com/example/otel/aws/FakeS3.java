package com.example.otel.aws;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Minimal S3 endpoint on localhost that answers every request with a one-bucket
 * {@code ListBuckets} response and records the request headers.
 */
final class FakeS3 implements AutoCloseable {

	private static final byte[] LIST_BUCKETS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<ListAllMyBucketsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">\
			<Owner><ID>owner</ID></Owner><Buckets><Bucket><Name>b1</Name></Bucket></Buckets>\
			</ListAllMyBucketsResult>""".getBytes(StandardCharsets.UTF_8);

	private final HttpServer server;

	private final List<Map<String, List<String>>> requestHeaders = new CopyOnWriteArrayList<>();

	private FakeS3(HttpServer server) {
		this.server = server;
	}

	static FakeS3 start() throws IOException {
		FakeS3 fake = new FakeS3(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
		fake.server.createContext("/", (exchange) -> {
			fake.requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/xml");
			exchange.sendResponseHeaders(200, LIST_BUCKETS.length);
			exchange.getResponseBody().write(LIST_BUCKETS);
			exchange.close();
		});
		fake.server.start();
		return fake;
	}

	String endpoint() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	List<Map<String, List<String>>> requestHeaders() {
		return this.requestHeaders;
	}

	String header(int request, String name) {
		return this.requestHeaders.get(request)
			.entrySet()
			.stream()
			.filter((entry) -> entry.getKey().equalsIgnoreCase(name))
			.map((entry) -> entry.getValue().get(0))
			.findFirst()
			.orElse(null);
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

}
