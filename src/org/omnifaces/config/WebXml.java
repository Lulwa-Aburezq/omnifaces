/*
 * Copyright 2012 OmniFaces.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.config;

import static org.omnifaces.util.Utils.isEmpty;
import static org.omnifaces.util.Utils.isNumber;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.faces.context.FacesContext;
import javax.faces.webapp.FacesServlet;
import javax.servlet.Filter;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextListener;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.omnifaces.util.Faces;
import org.omnifaces.util.Utils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This configuration enum parses the <code>/WEB-INF/web.xml</code> and all <code>/META-INF/web-fragment</code> files
 * found in the classpath and offers methods to obtain information from them which is not available by the standard
 * Servlet API.
 *
 * @author Bauke Scholtz
 * @since 1.2
 */
public enum WebXml {

	// Enum singleton -------------------------------------------------------------------------------------------------

	/**
	 * Returns the lazily loaded enum singleton instance.
	 * <p>
	 * Note: if this is needed in e.g. a {@link Filter} which is called before the {@link FacesServlet} is invoked,
	 * then it won't work if the <code>INSTANCE</code> hasn't been referenced before. Since JSF installs a special
	 * "init" {@link FacesContext} during startup, one option for doing this initial referencing is in a
	 * {@link ServletContextListener}. The data this enum encapsulates will then be available even where there is no
	 * {@link FacesContext} available. If there's no other option, then you need to manually invoke
	 * {@link #init(ServletContext)} whereby you pass the desired {@link ServletContext}.
	 */
	INSTANCE;

	// Private constants ----------------------------------------------------------------------------------------------

	private static final Logger logger = Logger.getLogger(WebXml.class.getName());

	private static final String WEB_XML = "/WEB-INF/web.xml";
	private static final String WEB_FRAGMENT_XML = "META-INF/web-fragment.xml";

	private static final String XPATH_WELCOME_FILE =
		"welcome-file-list/welcome-file";
	private static final String XPATH_EXCEPTION_TYPE =
		"error-page/exception-type";
	private static final String XPATH_LOCATION =
		"location";
	private static final String XPATH_ERROR_PAGE_500_LOCATION =
		"error-page[error-code=500]/location";
	private static final String XPATH_ERROR_PAGE_DEFAULT_LOCATION =
		"error-page[not(error-code) and not(exception-type)]/location";
	private static final String XPATH_FORM_LOGIN_PAGE =
			"login-config[auth-method='FORM']/form-login-config/form-login-page";
	private static final String XPATH_FORM_ERROR_PAGE =
			"login-config[auth-method='FORM']/form-login-config/form-error-page";
	private static final String XPATH_SECURITY_CONSTRAINT =
		"security-constraint";
	private static final String XPATH_WEB_RESOURCE_URL_PATTERN =
		"web-resource-collection/url-pattern";
	private static final String XPATH_AUTH_CONSTRAINT =
		"auth-constraint";
	private static final String XPATH_AUTH_CONSTRAINT_ROLE_NAME =
		"auth-constraint/role-name";
	private static final String XPATH_SESSION_TIMEOUT =
		"session-config/session-timeout";

	private static final String ERROR_NOT_INITIALIZED =
		"WebXml is not initialized yet. Please use #init(ServletContext) method to manually initialize it.";
	private static final String ERROR_URL_MUST_START_WITH_SLASH =
		"URL must start with '/': '%s'";
	private static final String LOG_INITIALIZATION_ERROR =
		"WebXml failed to initialize. Perhaps your web.xml contains a typo?";

	// Properties -----------------------------------------------------------------------------------------------------

	private AtomicBoolean initialized = new AtomicBoolean();
	private List<String> welcomeFiles;
	private Map<Class<Throwable>, String> errorPageLocations;
	private String formLoginPage;
	private String formErrorPage;
	private Map<String, Set<String>> securityConstraints;
	private int sessionTimeout;

	// Init -----------------------------------------------------------------------------------------------------------

	/**
	 * Perform automatic initialization whereby the servlet context is obtained from the faces context.
	 */
	private void init() {
		if (!initialized.get() && Faces.getContext() != null) {
			init(Faces.getServletContext());
		}
	}

	/**
	 * Perform manual initialization with the given servlet context, if not null and not already initialized yet.
	 * @param servletContext The servlet context to obtain the web.xml from.
	 * @return The current {@link WebXml} instance, initialized and all.
	 */
	public WebXml init(ServletContext servletContext) {
		if (servletContext != null && !initialized.getAndSet(true)) {
			try {
				Element webXml = loadWebXml(servletContext).getDocumentElement();
				XPath xpath = XPathFactory.newInstance().newXPath();
				welcomeFiles = parseWelcomeFiles(webXml, xpath);
				errorPageLocations = parseErrorPageLocations(webXml, xpath);
				formLoginPage = parseFormLoginPage(webXml, xpath);
				formErrorPage = parseFormErrorPage(webXml, xpath);
				securityConstraints = parseSecurityConstraints(webXml, xpath);
				sessionTimeout = parseSessionTimeout(webXml, xpath);
			}
			catch (Exception e) {
				logger.log(Level.SEVERE, LOG_INITIALIZATION_ERROR, e);
				throw new RuntimeException(e);
			}
		}

		return this;
	}

	// Actions --------------------------------------------------------------------------------------------------------

	/**
	 * Find for the given exception the right error page location. Exception types are matched as per Servlet 3.0
	 * specification 10.9.2:
	 * <ul>
	 *   <li>Make a first pass through all specific exception types. If an exact match is found, use its location.
	 *   <li>Else make a second pass through all specific exception types in the order as they are declared in
	 *       web.xml. If the current exception is an instance of it, then use its location.
	 *   <li>Else use the default error page location, which can be either the java.lang.Throwable or HTTP 500 or
	 *       default one.
	 * </ul>
	 * @param exception The exception to find the error page location for.
	 * @return The right error page location for the given exception.
	 */
	public String findErrorPageLocation(Throwable exception) {
		Map<Class<Throwable>, String> errorPageLocations = getErrorPageLocations();

		for (Entry<Class<Throwable>, String> entry : errorPageLocations.entrySet()) {
			if (entry.getKey() == exception.getClass()) {
				return entry.getValue();
			}
		}

		for (Entry<Class<Throwable>, String> entry : errorPageLocations.entrySet()) {
			if (entry.getKey() != null && entry.getKey().isInstance(exception)) {
				return entry.getValue();
			}
		}

		return errorPageLocations.get(null);
	}

	/**
	 * Returns <code>true</code> if access to the given URL is allowed for the given role. URL patterns are matched as
	 * per Servlet 3.0 specification 12.1:
	 * <ul>
	 *   <li>Make a first pass through all URL patterns. If an exact match is found, then check the role on it.
	 *   <li>Else make a recursive pass through all prefix URL patterns, stepping down the URL one directory at a time,
	 *       trying to find the longest path match. If it is found, then check the role on it.
	 *   <li>Else if the last segment in the URL path contains an extension, then make a last pass through all suffix
	 *       URL patterns. If a match is found, then check the role on it.
	 *   <li>Else assume it as unprotected resource and return <code>true</code>.
	 * </ul>
	 * @param url URL to be checked for access by the given role. It must start with '/' and be context-relative.
	 * @param role Role to be checked for access to the given URL.
	 * @return <code>true</code> if access to the given URL is allowed for the given role, otherwise <code>false</code>.
	 * @throws NullPointerException If given URL is null.
	 * @throws IllegalArgumentException If given URL does not start with '/'.
	 * @since 1.4
	 */
	public boolean isAccessAllowed(String url, String role) {
		Map<String, Set<String>> securityConstraints = getSecurityConstraints();

		if (url.charAt(0) != ('/')) {
			throw new IllegalArgumentException(String.format(ERROR_URL_MUST_START_WITH_SLASH, url));
		}

		if (url.length() > 1 && url.charAt(url.length() - 1) == '/') {
			url = url.substring(0, url.length() - 1); // Trim trailing slash.
		}

		for (Entry<String, Set<String>> entry : securityConstraints.entrySet()) {
			if (isExactMatch(entry.getKey(), url)) {
				return isRoleMatch(entry.getValue(), role);
			}
		}

		for (String path = url, urlMatch = ""; !path.isEmpty(); path = path.substring(0, path.lastIndexOf('/'))) {
			Boolean roleMatch = null;

			for (Entry<String, Set<String>> entry : securityConstraints.entrySet()) {
				if (urlMatch.length() < entry.getKey().length() && isPrefixMatch(entry.getKey(), path)) {
					urlMatch = entry.getKey();
					roleMatch = isRoleMatch(entry.getValue(), role);
				}
			}

			if (roleMatch != null) {
				return roleMatch;
			}
		}

		if (url.contains(".")) {
			for (Entry<String, Set<String>> entry : securityConstraints.entrySet()) {
				if (isSuffixMatch(url, entry.getKey())) {
					return isRoleMatch(entry.getValue(), role);
				}
			}
		}

		return true;
	}

	private static boolean isExactMatch(String urlPattern, String url) {
		return url.equals(urlPattern.endsWith("/*") ? urlPattern.substring(0, urlPattern.length() - 2) : urlPattern);
	}

	private static boolean isPrefixMatch(String urlPattern, String url) {
		return urlPattern.endsWith("/*") ? url.startsWith(urlPattern.substring(0, urlPattern.length() - 2)) : false;
	}

	private static boolean isSuffixMatch(String urlPattern, String url) {
		return urlPattern.startsWith("*.") ? url.endsWith(urlPattern.substring(1)) : false;
	}

	private static boolean isRoleMatch(Set<String> roles, String role) {
		return roles == null || roles.contains(role) || (role != null && roles.contains("*"));
	}

	// Getters --------------------------------------------------------------------------------------------------------

	/**
	 * Returns a list of all welcome files.
	 * @return A list of all welcome files.
	 * @since 1.4
	 */
	public List<String> getWelcomeFiles() {
		checkInitialized();
		return welcomeFiles;
	}

	/**
	 * Returns a mapping of all error page locations by exception type. The default location is identified by
	 * <code>null</code> key.
	 * @return A mapping of all error page locations by exception type.
	 */
	public Map<Class<Throwable>, String> getErrorPageLocations() {
		checkInitialized();
		return errorPageLocations;
	}

	/**
	 * Returns the location of the FORM authentication login page, or <code>null</code> if it is not defined.
	 * @return The location of the FORM authentication login page, or <code>null</code> if it is not defined.
	 */
	public String getFormLoginPage() {
		checkInitialized();
		return formLoginPage;
	}

	/**
	 * Returns the location of the FORM authentication error page, or <code>null</code> if it is not defined.
	 * @return The location of the FORM authentication error page, or <code>null</code> if it is not defined.
	 * @since 1.8
	 */
	public String getFormErrorPage() {
		checkInitialized();
		return formErrorPage;
	}

	/**
	 * Returns a mapping of all security constraint URL patterns and the associated roles in the declared order. If the
	 * roles is <code>null</code>, then it means that no auth constraint is been set (i.e. the resource is publicly
	 * accessible). If the roles is empty, then it means that an empty auth constraint is been set (i.e. the resource
	 * is in no way accessible).
	 * @return A mapping of all security constraint URL patterns and the associated roles in the declared order.
	 * @since 1.4
	 */
	public Map<String, Set<String>> getSecurityConstraints() {
		checkInitialized();
		return securityConstraints;
	}

	/**
	 * Returns the configured session timeout in minutes, or <code>-1</code> if it is not defined.
	 * @return The configured session timeout in minutes, or <code>-1</code> if it is not defined.
	 * @since 1.7
	 */
	public int getSessionTimeout() {
		checkInitialized();
		return sessionTimeout;
	}

	private void checkInitialized() {
		// This init() call is performed here instead of in constructor, because WebLogic loads this enum as a CDI
		// managed bean (in spite of having a VetoAnnotatedTypeExtension) which in turn implicitly invokes the enum
		// constructor and thus causes an init while JSF context isn't fully initialized and thus the faces context
		// isn't available yet. Perhaps it's fixed in newer WebLogic versions.
		init();

		if (!initialized.get()) {
			throw new IllegalStateException(ERROR_NOT_INITIALIZED);
		}
	}

	// Helpers --------------------------------------------------------------------------------------------------------

	/**
	 * Load, merge and return all <code>web.xml</code> and <code>web-fragment.xml</code> files found in the classpath
	 * into a single {@link Document}.
	 */
	private static Document loadWebXml(ServletContext context) throws Exception {
		DocumentBuilder builder = createDocumentBuilder();
		Document document = builder.newDocument();
		document.appendChild(document.createElement("web"));
		URL url = context.getResource(WEB_XML);

		if (url != null) { // Since Servlet 3.0, web.xml is optional.
			parseAndAppendChildren(url, builder, document);
		}

		if (context.getMajorVersion() >= 3) { // web-fragment.xml exist only since Servlet 3.0.
			Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources(WEB_FRAGMENT_XML);

			while (urls.hasMoreElements()) {
				parseAndAppendChildren(urls.nextElement(), builder, document);
			}
		}

		return document;
	}

	/**
	 * Returns an instance of {@link DocumentBuilder} which doesn't validate, nor is namespace aware nor expands entity
	 * references (to keep it as lenient as possible).
	 */
	private static DocumentBuilder createDocumentBuilder() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(false);
		factory.setNamespaceAware(false);
		factory.setExpandEntityReferences(false);
		return factory.newDocumentBuilder();
	}

	/**
	 * Parse the given URL as a document using the given builder and then append all its child nodes to the given
	 * document.
	 */
	private static void parseAndAppendChildren(URL url, DocumentBuilder builder, Document document) throws Exception {
		URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		InputStream input = null;

		try {
			input = connection.getInputStream();
			NodeList children = builder.parse(input).getDocumentElement().getChildNodes();

			for (int i = 0; i < children.getLength(); i++) {
				document.getDocumentElement().appendChild(document.importNode(children.item(i), true));
			}
		}
		finally {
			Utils.close(input);
		}
	}

	/**
	 * Create and return a list of all welcome files.
	 */
	private static List<String> parseWelcomeFiles(Element webXml, XPath xpath) throws Exception {
		NodeList welcomeFileList = getNodeList(webXml, xpath, XPATH_WELCOME_FILE);
		List<String> welcomeFiles = new ArrayList<String>(welcomeFileList.getLength());

		for (int i = 0; i < welcomeFileList.getLength(); i++) {
			welcomeFiles.add(welcomeFileList.item(i).getTextContent().trim());
		}

		return Collections.unmodifiableList(welcomeFiles);
	}

	/**
	 * Create and return a mapping of all error page locations by exception type found in the given document.
	 */
	@SuppressWarnings("unchecked") // For the cast on Class<Throwable>.
	private static Map<Class<Throwable>, String> parseErrorPageLocations(Element webXml, XPath xpath) throws Exception {
		Map<Class<Throwable>, String> errorPageLocations = new LinkedHashMap<Class<Throwable>, String>();
		NodeList exceptionTypes = getNodeList(webXml, xpath, XPATH_EXCEPTION_TYPE);

		for (int i = 0; i < exceptionTypes.getLength(); i++) {
			Node node = exceptionTypes.item(i);
			Class<Throwable> exceptionClass = (Class<Throwable>) Class.forName(node.getTextContent().trim());
			String exceptionLocation = xpath.compile(XPATH_LOCATION).evaluate(node.getParentNode()).trim();
			Class<Throwable> key = (exceptionClass == Throwable.class) ? null : exceptionClass;

			if (!errorPageLocations.containsKey(key)) {
				errorPageLocations.put(key, exceptionLocation);
			}
		}

		if (!errorPageLocations.containsKey(null)) {
			String defaultLocation = xpath.compile(XPATH_ERROR_PAGE_500_LOCATION).evaluate(webXml).trim();

			if (isEmpty(defaultLocation)) {
				defaultLocation = xpath.compile(XPATH_ERROR_PAGE_DEFAULT_LOCATION).evaluate(webXml).trim();
			}

			if (!isEmpty(defaultLocation)) {
				errorPageLocations.put(null, defaultLocation);
			}
		}

		return Collections.unmodifiableMap(errorPageLocations);
	}

	/**
	 * Return the location of the FORM authentication login page.
	 */
	private static String parseFormLoginPage(Element webXml, XPath xpath) throws Exception {
		String formLoginPage = xpath.compile(XPATH_FORM_LOGIN_PAGE).evaluate(webXml).trim();
		return isEmpty(formLoginPage) ? null : formLoginPage;
	}

	/**
	 * Return the location of the FORM authentication error page.
	 */
	private static String parseFormErrorPage(Element webXml, XPath xpath) throws Exception {
		String formErrorPage = xpath.compile(XPATH_FORM_ERROR_PAGE).evaluate(webXml).trim();
		return isEmpty(formErrorPage) ? null : formErrorPage;
	}

	/**
	 * Create and return a mapping of all security constraint URL patterns and the associated roles.
	 */
	private static Map<String, Set<String>> parseSecurityConstraints(Element webXml, XPath xpath) throws Exception {
		Map<String, Set<String>> securityConstraints = new LinkedHashMap<String, Set<String>>();
		NodeList constraints = getNodeList(webXml, xpath, XPATH_SECURITY_CONSTRAINT);

		for (int i = 0; i < constraints.getLength(); i++) {
			Node constraint = constraints.item(i);
			Set<String> roles = null;
			NodeList auth = getNodeList(constraint, xpath, XPATH_AUTH_CONSTRAINT);

			if (auth.getLength() > 0) {
				NodeList authRoles = getNodeList(constraint, xpath, XPATH_AUTH_CONSTRAINT_ROLE_NAME);
				roles = new HashSet<String>(authRoles.getLength());

				for (int j = 0; j < authRoles.getLength(); j++) {
					roles.add(authRoles.item(j).getTextContent().trim());
				}

				roles = Collections.unmodifiableSet(roles);
			}

			NodeList urlPatterns = getNodeList(constraint, xpath, XPATH_WEB_RESOURCE_URL_PATTERN);

			for (int j = 0; j < urlPatterns.getLength(); j++) {
				String urlPattern = urlPatterns.item(j).getTextContent().trim();
				securityConstraints.put(urlPattern, roles);
			}
		}

		return Collections.unmodifiableMap(securityConstraints);
	}

	/**
	 * Return the configured session timeout in minutes, or <code>-1</code> if it is not defined.
	 */
	private static int parseSessionTimeout(Element webXml, XPath xpath) throws Exception {
		String sessionTimeout = xpath.compile(XPATH_SESSION_TIMEOUT).evaluate(webXml).trim();
		return isNumber(sessionTimeout) ? Integer.parseInt(sessionTimeout) : -1;
	}

	// Helpers of helpers (JAXP hell) ---------------------------------------------------------------------------------

	private static NodeList getNodeList(Node node, XPath xpath, String expression) throws Exception {
		return (NodeList) xpath.compile(expression).evaluate(node, XPathConstants.NODESET);
	}

}