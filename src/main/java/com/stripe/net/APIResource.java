package com.stripe.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLStreamHandler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.List;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.stripe.Stripe;
import com.stripe.exception.APIConnectionException;
import com.stripe.exception.APIException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.EventData;
import com.stripe.model.EventDataDeserializer;
import com.stripe.model.ChargeRefundCollection;
import com.stripe.model.ChargeRefundCollectionDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.StripeRawJsonObject;
import com.stripe.model.StripeRawJsonObjectDeserializer;
import com.stripe.model.FeeRefundCollection;
import com.stripe.model.FeeRefundCollectionDeserializer;

public abstract class APIResource extends StripeObject {

	public static final Gson GSON = new GsonBuilder()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.registerTypeAdapter(EventData.class, new EventDataDeserializer())
			.registerTypeAdapter(ChargeRefundCollection.class, new ChargeRefundCollectionDeserializer())
			.registerTypeAdapter(FeeRefundCollection.class, new FeeRefundCollectionDeserializer())
			.registerTypeAdapter(StripeRawJsonObject.class, new StripeRawJsonObjectDeserializer())
			.create();

	private static String className(Class<?> clazz) {
		String className = clazz.getSimpleName().toLowerCase().replace("$", " ");

		// TODO: Delurk this, with invoiceitem being a valid url, we can't get too
		// fancy yet.
		if (className.equals("applicationfee")) {
			return "application_fee";
		} else {
			return className;
		}
	}

	protected static String singleClassURL(Class<?> clazz) {
		return String.format("%s/v1/%s", Stripe.getApiBase(), className(clazz));
	}

	protected static String classURL(Class<?> clazz) {
		return String.format("%ss", singleClassURL(clazz));
	}

	protected static String instanceURL(Class<?> clazz, String id) throws InvalidRequestException {
		try {
			return String.format("%s/%s", classURL(clazz), urlEncode(id));
		} catch (UnsupportedEncodingException e) {
			throw new InvalidRequestException("Unable to encode parameters to "
					+ CHARSET
					+ ". Please contact support@stripe.com for assistance.",
					null, e);
		}
	}

	public static final String CHARSET = "UTF-8";

	private static final String DNS_CACHE_TTL_PROPERTY_NAME = "networkaddress.cache.ttl";

	/*
	 * Set this property to override your environment's default
	 * URLStreamHandler; Settings the property should not be needed in most
	 * environments.
	 */
	private static final String CUSTOM_URL_STREAM_HANDLER_PROPERTY_NAME = "com.stripe.net.customURLStreamHandler";

	protected enum RequestMethod {
		GET, POST, DELETE
	}

	private static String urlEncode(String str) throws UnsupportedEncodingException {
		// Preserve original behavior that passing null for an object id will lead
		// to us actually making a request to /v1/foo/null
		if (str == null) {
			return null;
		}
		else {
			return URLEncoder.encode(str, CHARSET);
		}
	}

	private static String urlEncodePair(String k, String v)
			throws UnsupportedEncodingException {
		return String.format("%s=%s", urlEncode(k), urlEncode(v));
	}

	static Map<String, String> getHeaders(String apiKey) {
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Accept-Charset", CHARSET);
		headers.put("User-Agent",
				String.format("Stripe/v1 JavaBindings/%s", Stripe.VERSION));

		if (apiKey == null) {
			apiKey = Stripe.apiKey;
		}

		headers.put("Authorization", String.format("Bearer %s", apiKey));

		// debug headers
		String[] propertyNames = { "os.name", "os.version", "os.arch",
				"java.version", "java.vendor", "java.vm.version",
				"java.vm.vendor" };
		Map<String, String> propertyMap = new HashMap<String, String>();
		for (String propertyName : propertyNames) {
			propertyMap.put(propertyName, System.getProperty(propertyName));
		}
		propertyMap.put("bindings.version", Stripe.VERSION);
		propertyMap.put("lang", "Java");
		propertyMap.put("publisher", "Stripe");
		headers.put("X-Stripe-Client-User-Agent", GSON.toJson(propertyMap));
		if (Stripe.apiVersion != null) {
			headers.put("Stripe-Version", Stripe.apiVersion);
		}
		return headers;
	}

	private static java.net.HttpURLConnection createStripeConnection(
			String url, String apiKey) throws IOException {
		URL stripeURL = null;
		String customURLStreamHandlerClassName = System.getProperty(
				CUSTOM_URL_STREAM_HANDLER_PROPERTY_NAME, null);
		if (customURLStreamHandlerClassName != null) {
			// instantiate the custom handler provided
			try {
				Class<URLStreamHandler> clazz = (Class<URLStreamHandler>) Class
						.forName(customURLStreamHandlerClassName);
				Constructor<URLStreamHandler> constructor = clazz
						.getConstructor();
				URLStreamHandler customHandler = constructor.newInstance();
				stripeURL = new URL(null, url, customHandler);
			} catch (ClassNotFoundException e) {
				throw new IOException(e);
			} catch (SecurityException e) {
				throw new IOException(e);
			} catch (NoSuchMethodException e) {
				throw new IOException(e);
			} catch (IllegalArgumentException e) {
				throw new IOException(e);
			} catch (InstantiationException e) {
				throw new IOException(e);
			} catch (IllegalAccessException e) {
				throw new IOException(e);
			} catch (InvocationTargetException e) {
				throw new IOException(e);
			}
		} else {
			stripeURL = new URL(url);
		}
		java.net.HttpURLConnection conn = (java.net.HttpURLConnection) stripeURL.openConnection();
		conn.setConnectTimeout(30 * 1000);
		conn.setReadTimeout(80 * 1000);
		conn.setUseCaches(false);
		for (Map.Entry<String, String> header : getHeaders(apiKey).entrySet()) {
			conn.setRequestProperty(header.getKey(), header.getValue());
		}

		return conn;
	}

	private static void throwInvalidCertificateException() throws APIConnectionException {
		throw new APIConnectionException("Invalid server certificate. You tried to connect to a server that has a revoked SSL certificate, which means we cannot securely send data to that server. Please email support@stripe.com if you need help connecting to the correct API server.");
	}

	private static void checkSSLCert(java.net.HttpURLConnection hconn) throws IOException, APIConnectionException {
		if (!Stripe.getVerifySSL() && !hconn.getURL().getHost().equals("api.stripe.com")) {
			return;
		}

		javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) hconn;
		conn.connect();

		Certificate[] certs = conn.getServerCertificates();

		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");

			byte[] der = certs[0].getEncoded();
			md.update(der);
			byte[] digest = md.digest();

			byte[] revokedCertDigest = {(byte) 0x05, (byte) 0xc0, (byte) 0xb3, (byte) 0x64, (byte) 0x36, (byte) 0x94, (byte) 0x47, (byte) 0x0a, (byte) 0x88, (byte) 0x8c, (byte) 0x6e, (byte) 0x7f, (byte) 0xeb, (byte) 0x5c, (byte) 0x9e, (byte) 0x24, (byte) 0xe8, (byte) 0x23, (byte) 0xdc, (byte) 0x53};

			if (Arrays.equals(digest, revokedCertDigest)) {
				throwInvalidCertificateException();
			}

		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		} catch (CertificateEncodingException e) {
			throwInvalidCertificateException();
		}
	}

	private static String formatURL(String url, String query) {
		if (query == null || query.isEmpty()) {
			return url;
		} else {
			// In some cases, URL can already contain a question mark (eg, upcoming invoice lines)
			String separator = url.contains("?") ? "&" : "?";
			return String.format("%s%s%s", url, separator, query);
		}
	}

	private static java.net.HttpURLConnection createGetConnection(
			String url, String query, String apiKey) throws IOException, APIConnectionException {
		String getURL = formatURL(url, query);
		java.net.HttpURLConnection conn = createStripeConnection(getURL,
				apiKey);
		conn.setRequestMethod("GET");

		checkSSLCert(conn);

		return conn;
	}

	private static java.net.HttpURLConnection createPostConnection(
			String url, String query, String apiKey) throws IOException, APIConnectionException {
		java.net.HttpURLConnection conn = createStripeConnection(url,
				apiKey);

		conn.setDoOutput(true);
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", String.format(
				"application/x-www-form-urlencoded;charset=%s", CHARSET));

		checkSSLCert(conn);

		OutputStream output = null;
		try {
			output = conn.getOutputStream();
			output.write(query.getBytes(CHARSET));
		} finally {
			if (output != null) {
				output.close();
			}
		}
		return conn;
	}

	private static java.net.HttpURLConnection createDeleteConnection(
			String url, String query, String apiKey) throws IOException, APIConnectionException {
		String deleteUrl = formatURL(url, query);
		java.net.HttpURLConnection conn = createStripeConnection(
				deleteUrl, apiKey);
		conn.setRequestMethod("DELETE");

		checkSSLCert(conn);

		return conn;
	}

	private static String createQuery(Map<String, Object> params)
			throws UnsupportedEncodingException, InvalidRequestException {
		Map<String, String> flatParams = flattenParams(params);
		StringBuilder queryStringBuffer = new StringBuilder();
		for (Map.Entry<String, String> entry : flatParams.entrySet()) {
			if (queryStringBuffer.length() > 0) {
				queryStringBuffer.append("&");
			}
			queryStringBuffer.append(urlEncodePair(entry.getKey(),
					entry.getValue()));
		}
		return queryStringBuffer.toString();
	}

	private static Map<String, String> flattenParams(Map<String, Object> params)
			throws InvalidRequestException {
		if (params == null) {
			return new HashMap<String, String>();
		}
		Map<String, String> flatParams = new HashMap<String, String>();
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (value instanceof Map<?, ?>) {
				Map<String, Object> flatNestedMap = new HashMap<String, Object>();
				Map<?, ?> nestedMap = (Map<?, ?>) value;
				for (Map.Entry<?, ?> nestedEntry : nestedMap.entrySet()) {
					flatNestedMap.put(
							String.format("%s[%s]", key, nestedEntry.getKey()),
							nestedEntry.getValue());
				}
				flatParams.putAll(flattenParams(flatNestedMap));
			} else if ("".equals(value)) {
					throw new InvalidRequestException("You cannot set '"+key+"' to an empty string. "+
										"We interpret empty strings as null in requests. "+
										"You may set '"+key+"' to null to delete the property.",
										key, null);
			} else if (value == null) {
				flatParams.put(key, "");
			} else if (value != null) {
				flatParams.put(key, value.toString());
			}
		}
		return flatParams;
	}

	// represents Errors returned as JSON
	private static class ErrorContainer {
		private APIResource.Error error;
	}

	private static class Error {
		@SuppressWarnings("unused")
		String type;

		String message;

		String code;

		String param;
	}

	private static String getResponseBody(InputStream responseStream)
			throws IOException {
		//\A is the beginning of
		// the stream boundary
		String rBody = new Scanner(responseStream, CHARSET)
												.useDelimiter("\\A")
												.next(); //

		responseStream.close();
		return rBody;
	}

	private static StripeResponse makeURLConnectionRequest(
			APIResource.RequestMethod method, String url, String query,
			String apiKey) throws APIConnectionException {
		java.net.HttpURLConnection conn = null;
		try {
			switch (method) {
			case GET:
				conn = createGetConnection(url, query, apiKey);
				break;
			case POST:
				conn = createPostConnection(url, query, apiKey);
				break;
			case DELETE:
				conn = createDeleteConnection(url, query, apiKey);
				break;
			default:
				throw new APIConnectionException(
						String.format(
								"Unrecognized HTTP method %s. "
										+ "This indicates a bug in the Stripe bindings. Please contact "
										+ "support@stripe.com for assistance.",
								method));
			}
			// trigger the request
			int rCode = conn.getResponseCode();
			String rBody = null;
			Map<String, List<String>> headers;

			if (rCode >= 200 && rCode < 300) {
				rBody = getResponseBody(conn.getInputStream());
			} else {
				rBody = getResponseBody(conn.getErrorStream());
			}
			headers = conn.getHeaderFields();
			return new StripeResponse(rCode, rBody, headers);

		} catch (IOException e) {
			throw new APIConnectionException(
					String.format(
							"Could not connect to Stripe (%s). "
									+ "Please check your internet connection and try again. If this problem persists,"
									+ "you should check Stripe's service status at https://twitter.com/stripestatus,"
									+ " or let us know at support@stripe.com.",
							Stripe.getApiBase()), e);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	protected static <T> T request(APIResource.RequestMethod method,
			String url, Map<String, Object> params, Class<T> clazz,
			String apiKey) throws AuthenticationException,
			InvalidRequestException, APIConnectionException, CardException,
			APIException {
		String originalDNSCacheTTL = null;
		Boolean allowedToSetTTL = true;
		try {
			originalDNSCacheTTL = java.security.Security
					.getProperty(DNS_CACHE_TTL_PROPERTY_NAME);
			// disable DNS cache
			java.security.Security
					.setProperty(DNS_CACHE_TTL_PROPERTY_NAME, "0");
		} catch (SecurityException se) {
			allowedToSetTTL = false;
		}

		try {
			return _request(method, url, params, clazz, apiKey);
		} finally {
			if (allowedToSetTTL) {
				if (originalDNSCacheTTL == null) {
					// value unspecified by implementation
					// DNS_CACHE_TTL_PROPERTY_NAME of -1 = cache forever
					java.security.Security.setProperty(DNS_CACHE_TTL_PROPERTY_NAME, "-1");
				} else {
					java.security.Security.setProperty(
							DNS_CACHE_TTL_PROPERTY_NAME, originalDNSCacheTTL);
				}
			}
		}
	}

	protected static <T> T _request(APIResource.RequestMethod method,
			String url, Map<String, Object> params, Class<T> clazz,
			String apiKey) throws AuthenticationException,
			InvalidRequestException, APIConnectionException, CardException,
			APIException {
		if ((Stripe.apiKey == null || Stripe.apiKey.length() == 0)
				&& (apiKey == null || apiKey.length() == 0)) {
			throw new AuthenticationException(
					"No API key provided. (HINT: set your API key using 'Stripe.apiKey = <API-KEY>'. "
							+ "You can generate API keys from the Stripe web interface. "
							+ "See https://stripe.com/api for details or email support@stripe.com if you have questions.");
		}

		if (apiKey == null) {
			apiKey = Stripe.apiKey;
		}

		String query;

		try {
			query = createQuery(params);
		} catch (UnsupportedEncodingException e) {
			throw new InvalidRequestException("Unable to encode parameters to "
					+ CHARSET
					+ ". Please contact support@stripe.com for assistance.",
					null, e);
		}

		StripeResponse response;
		try {
			// HTTPSURLConnection verifies SSL cert by default
			response = makeURLConnectionRequest(method, url, query, apiKey);
		} catch (ClassCastException ce) {
			// appengine doesn't have HTTPSConnection, use URLFetch API
			String appEngineEnv = System.getProperty(
					"com.google.appengine.runtime.environment", null);
			if (appEngineEnv != null) {
				response = makeAppEngineRequest(method, url, query, apiKey);
			} else {
				// non-appengine ClassCastException
				throw ce;
			}
		}
		int rCode = response.responseCode;
		String rBody = response.responseBody;
		if (rCode < 200 || rCode >= 300) {
			handleAPIError(rBody, rCode);
		}
		return GSON.fromJson(rBody, clazz);
	}

	private static void handleAPIError(String rBody, int rCode)
			throws InvalidRequestException, AuthenticationException,
			CardException, APIException {
		APIResource.Error error = GSON.fromJson(rBody,
				APIResource.ErrorContainer.class).error;
		switch (rCode) {
		case 400:
			throw new InvalidRequestException(error.message, error.param, null);
		case 404:
			throw new InvalidRequestException(error.message, error.param, null);
		case 401:
			throw new AuthenticationException(error.message);
		case 402:
			throw new CardException(error.message, error.code, error.param, null);
		default:
			throw new APIException(error.message, null);
		}
	}

	/*
	 * This is slower than usual because of reflection but avoids having to
	 * maintain AppEngine-specific JAR
	 */
	private static StripeResponse makeAppEngineRequest(RequestMethod method,
			String url, String query, String apiKey) throws APIException {
		String unknownErrorMessage = "Sorry, an unknown error occurred while trying to use the "
				+ "Google App Engine runtime. Please contact support@stripe.com for assistance.";
		try {
			if (method == RequestMethod.GET || method == RequestMethod.DELETE) {
				url = String.format("%s?%s", url, query);
			}
			URL fetchURL = new URL(url);

			Class<?> requestMethodClass = Class
					.forName("com.google.appengine.api.urlfetch.HTTPMethod");
			Object httpMethod = requestMethodClass.getDeclaredField(
					method.name()).get(null);

			Class<?> fetchOptionsBuilderClass = Class
					.forName("com.google.appengine.api.urlfetch.FetchOptions$Builder");
			Object fetchOptions = null;
			try {
				fetchOptions = fetchOptionsBuilderClass.getDeclaredMethod(
						"validateCertificate").invoke(null);
			} catch (NoSuchMethodException e) {
				System.err
						.println("Warning: this App Engine SDK version does not allow verification of SSL certificates;"
								+ "this exposes you to a MITM attack. Please upgrade your App Engine SDK to >=1.5.0. "
								+ "If you have questions, contact support@stripe.com.");
				fetchOptions = fetchOptionsBuilderClass.getDeclaredMethod(
						"withDefaults").invoke(null);
			}

			Class<?> fetchOptionsClass = Class
					.forName("com.google.appengine.api.urlfetch.FetchOptions");

			// GAE requests can time out after 60 seconds, so make sure we leave
			// some time for the application to handle a slow Stripe
			fetchOptionsClass.getDeclaredMethod("setDeadline",
					java.lang.Double.class)
					.invoke(fetchOptions, new Double(55));

			Class<?> requestClass = Class
					.forName("com.google.appengine.api.urlfetch.HTTPRequest");

			Object request = requestClass.getDeclaredConstructor(URL.class,
					requestMethodClass, fetchOptionsClass).newInstance(
					fetchURL, httpMethod, fetchOptions);

			if (method == RequestMethod.POST) {
				requestClass.getDeclaredMethod("setPayload", byte[].class)
						.invoke(request, query.getBytes());
			}

			for (Map.Entry<String, String> header : getHeaders(apiKey)
					.entrySet()) {
				Class<?> httpHeaderClass = Class
						.forName("com.google.appengine.api.urlfetch.HTTPHeader");
				Object reqHeader = httpHeaderClass.getDeclaredConstructor(
						String.class, String.class).newInstance(
						header.getKey(), header.getValue());
				requestClass.getDeclaredMethod("setHeader", httpHeaderClass)
						.invoke(request, reqHeader);
			}

			Class<?> urlFetchFactoryClass = Class
					.forName("com.google.appengine.api.urlfetch.URLFetchServiceFactory");
			Object urlFetchService = urlFetchFactoryClass.getDeclaredMethod(
					"getURLFetchService").invoke(null);

			Method fetchMethod = urlFetchService.getClass().getDeclaredMethod(
					"fetch", requestClass);
			fetchMethod.setAccessible(true);
			Object response = fetchMethod.invoke(urlFetchService, request);

			int responseCode = (Integer) response.getClass()
					.getDeclaredMethod("getResponseCode").invoke(response);
			String body = new String((byte[]) response.getClass()
					.getDeclaredMethod("getContent").invoke(response), CHARSET);
			return new StripeResponse(responseCode, body);
		} catch (InvocationTargetException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (MalformedURLException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (NoSuchFieldException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (SecurityException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (NoSuchMethodException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (ClassNotFoundException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (IllegalArgumentException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (IllegalAccessException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (InstantiationException e) {
			throw new APIException(unknownErrorMessage, e);
		} catch (UnsupportedEncodingException e) {
			throw new APIException(unknownErrorMessage, e);
		}
	}

}
