/**
 * Helios, OpenSource Monitoring
 * Brought to you by the Helios Development Group
 *
 * Copyright 2007, Helios Development Group and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org. 
 *
 */
package org.helios.collectors.url;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.contrib.ssl.EasySSLProtocolSocketFactory;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.ssl.KeyMaterial;
import org.helios.collectors.SocketAbstractCollector;
import org.helios.collectors.CollectionResult;
import org.helios.collectors.exceptions.CollectorException;
import org.helios.collectors.exceptions.CollectorInitException;
import org.helios.collectors.exceptions.CollectorStartException;
import org.helios.helpers.StringHelper;
import org.helios.jmx.dynamic.annotations.JMXAttribute;
import org.helios.jmx.dynamic.annotations.JMXManagedObject;
import org.helios.jmx.dynamic.annotations.options.AttributeMutabilityOption;

/**
 * 
 * <p>Title: URLCollector </p>
 * <p>Description: Checks URL end points and traces availability and other statistics.</p> 
 * <p>Company: Helios Development Group</p>
 * @author Sandeep Malhotra (smalhotra@heliosdev.org)
 */
@JMXManagedObject (declared=false, annotated=true)
public class URLCollector extends SocketAbstractCollector {

	/** Endpoint URL that collector needs to monitor */
	protected URL url=null;
	
	/** Extracted value for host from endpoint URL */
	private String host=null;
	
	/** Endpooint port */
	private int port=80;
	
	/** Timeout in milliseconds for initial HTTP/S connection*/
	protected int timeout=5000; 
	
	/** String pattern to be matched match in response to determine endpoint availability*/
	protected String successContentMatch=null;
	
	/** 
	 * String pattern to be matched in response to determine endpoint availability.  
	 * If both success and failure patterns are provided, and they both match then 
	 * result of a failure match would determine the overall availability.  
	 * 
	 * some examples:
	 * success pattern = matched, failure pattern: not specified - Availability = true
	 * success pattern = not specified, failure pattern: matched - Availability = false
	 * success pattern = matched, failure pattern = matched - Availability = false
	 */
	protected String failureContentMatch=null;
	
	/** Success Pattern */
	protected Pattern successContentPattern=null;
	/** Failure Pattern */
	protected Pattern failureContentPattern=null;
	/** Flag to indicate availability of the endpoint */
	protected boolean available=false;
	/** Possible Auth_Types for an endpoint */
	protected enum AUTH_TYPE{
		NONE,
		BASIC,
		CLIENT_CERT
	}
	/** Auth_Type for the current endpoint */
	protected AUTH_TYPE authType=AUTH_TYPE.NONE;
	/** User Name for BASIC Auth_Type */
	protected String userName=null;
	/** Password for BASIC Auth_Type */
	protected String password=null;
	/** KeyStore file location for CLIENT-CERT Auth_Type */
	protected String keyStoreLocation=null;
	/** Passphrase for keystore file for CLIENT-CERT Auth_Type */
	protected String keyStorePassphrase=null;
	/** Http CLient object */
	protected HttpClient httpClient=null;
	/** Reference to HTTP GET method */
	protected GetMethod getMethod = null;
	/** Reference to HTTP POST method */
	protected PostMethod postMethod = null;
	/** Default protocol for SSL endpoints */
	protected static final String HTTPS_PROTOCOL="https";
	/** Default port for SSL endpoints */
	protected static final int DEFAULT_SSL_PORT=443;
	/** Internal counter used to create custom HTTPS protocol for CLIENT-CERT endpoints*/
	private static AtomicInteger uniqueCounter=new AtomicInteger(0);
	/** Custom prefix for SSL CLIENT-CERT endpoints */
	protected String myProtocolPrefix=null;
	/** Whether it's SOAP or REST style */
	protected String wsStyle = "REST";
	/** Flag to indicate whether the current endpoint is web service or not*/
	protected boolean isWebServiceEndpoint=false;
	/** URL collector version */
	private static final String URL_COLLECTOR_VERSION="0.1";
	
	private static boolean isSSLFactoryInitialized = false;
	
	private final int BYTES_TO_READ = 3000;
	
	/**
	 * This static block re-registers HTTPS protocol with EasySSLProtocolSocketFactory to
	 * trust web sites that presents self-signed certificates.  
	 */
	static{
		try{
			EasySSLProtocolSocketFactory easySSLPSFactory = new EasySSLProtocolSocketFactory();	
			Protocol httpsProtocol = new Protocol(HTTPS_PROTOCOL,(ProtocolSocketFactory) easySSLPSFactory, DEFAULT_SSL_PORT);
			Protocol.registerProtocol(HTTPS_PROTOCOL, httpsProtocol);
			isSSLFactoryInitialized = true;
		}catch(Exception ex){ isSSLFactoryInitialized = false; }
	}

	/**
	 * Only constructor for URLCollector class
	 */
	public URLCollector(String url) {
		super();
		setUrl(url);
	}
	

	/**
	 * Implementation of abstract method in Base class (AbstractCollector) for tasks 
	 * that needs to be done before this collector is started. 
	 */
	public void startCollector() throws CollectorStartException{
		if(!isSSLFactoryInitialized){
			throw new CollectorStartException("An error occured while initializing EasySSLProtocolSocketFactory: "+ this.getBeanName());
		}
		if(this.url != null && getHost() != null && getPort() > 0){
			httpClient = new HttpClient();
			if(getPortTunnel()!=null){
				StringBuilder newUrl = new StringBuilder(url.getProtocol()+"://"+getPortTunnel().getLocalHostName()+
				   ":"+getPortTunnel().getLocalPort());
				newUrl.append(url.getPath()==null?"":url.getPath());
				newUrl.append(url.getQuery()==null?"":"?"+url.getQuery());
				log.info("$$$$$$$$$$$$ Port tunnel is active so new URL is: "+newUrl);
				try{
					this.url = new URL(newUrl.toString());
				}catch(MalformedURLException muex){
					throw new CollectorStartException("An error occured while recreating new URL based on port tunnel provided: "+newUrl, muex);
				}
			}
			initializeHttpMethod(this.url.toString());
			/**
			 * check whether call to initializeHttpMethod resulted in any issue.
			 * If yes, then that method would have set the CollectorState to 
			 * START_FAILED, so just return without any further processing.
			 */
			if(getState()==CollectorState.START_FAILED){
				throw new CollectorStartException("Endpoint style is either missing or invalid for web service collector bean: "+ this.getBeanName());	
			}
			httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(timeout);
		} else {
			log.error("Invalid URL string provided for collector bean: "+this.getBeanName());
			throw new CollectorStartException("Invalid URL string provided for collector bean: "+this.getBeanName());	
		}

		if(authType == AUTH_TYPE.BASIC){
			if(userName != null && ! (userName.trim().length() == 0) && password != null && ! (password.trim().length() == 0)){
				Credentials credentials = new UsernamePasswordCredentials(userName, password);
				httpClient.getState().setCredentials(new AuthScope(host, port),	credentials);
				httpClient.getParams().setAuthenticationPreemptive(true);
				if(getMethod!=null){
					getMethod.setDoAuthentication(true);
				} else {
					postMethod.setDoAuthentication(true);
				}				
			}else{
				log.error("Check username and password provided for collector bean: "+this.getBeanName());
				throw new CollectorStartException("Check username and password provided for collector bean: "+this.getBeanName());	
			}
		} else if (authType == AUTH_TYPE.CLIENT_CERT){
			if(keyStoreLocation != null && ! (keyStoreLocation.trim().length() == 0) && keyStorePassphrase != null && ! (keyStorePassphrase.trim().length() == 0)) {
				try{
					registerProtocolCertificate();
					httpClient.getHostConfiguration().setHost(host, port,Protocol.getProtocol(myProtocolPrefix));
					//log.trace("URL with custom protocol is: "+this.url.toString().replace(HTTPS_PROTOCOL, myProtocolPrefix));
					initializeHttpMethod(this.url.toString().replace(HTTPS_PROTOCOL, myProtocolPrefix));
					/**
					 * check whether call to initializeHttpMethod resulted in any issue.
					 * If yes, then that method would have set the CollectorState to 
					 * START_FAILED, so just return without any further processing.
					 */
					if(getState()==CollectorState.START_FAILED){
						throw new CollectorStartException("Endpoint style is either missing or invalid for web service collector bean: "+ this.getBeanName());
					}			
				}catch(Exception ex){
					log.error("Unable to register secure protocol for URL [ "+this.url+" ] of bean: " + this.getBeanName());
					throw new CollectorStartException("Unable to register secure protocol for URL [ "+this.url+" ] of bean: " + this.getBeanName(), ex);
				}
			} else {
				log.error("KeyStoreLocation and/or KeyStorePassphrase is missing for a secure URL of bean: " + this.getBeanName());
				throw new CollectorStartException("KeyStoreLocation and/or KeyStorePassphrase is missing for a secure URL of bean: " + this.getBeanName());
			}
		}
		log.trace("Object [ "+getObjectName()+" ]"+getState());
	}
	
	private void initializeHttpMethod(String url) throws CollectorStartException{
		try{
			if(!isWebServiceEndpoint){
				getMethod = new GetMethod(url);
			}else{
				if(wsStyle.equalsIgnoreCase("REST")){
					getMethod = new GetMethod(url);
				}else if(wsStyle.equalsIgnoreCase("SOAP")){
					postMethod = new PostMethod(url);
				}else{
					log.error("Endpoint style is either missing or invalid for web service collector bean: "+ this.getBeanName());
					setState(CollectorState.START_FAILED);
				}
			}
		}catch(IllegalArgumentException iaex){
			log.error("Invalid URI passed for collector bean: "+ this.getBeanName()+ " - " + url);
			throw new CollectorStartException("Invalid URI passed for collector bean: "+ this.getBeanName()+ " - " + url,iaex);
		}catch(IllegalStateException isex){
			log.error("Unrecognized protocol for URI passed for collector bean: "+ this.getBeanName()+ " - " + url);
			throw new CollectorStartException("Unrecognized protocol for URI passed for collector bean: "+ this.getBeanName()+ " - " + url,isex);
		}
	}
	
	/**
	 * This method does the following:
	 * 1. Creates a new and unique protocol for each SSL URL that is secured by client certificate
	 * 2. Bind keyStore related information to this protocol
	 * 3. Registers it with HTTP Protocol object 
	 * 4. Stores the local reference for this custom protocol for use during furture collect calls
	 * 
	 *  @throws Exception
	 */
	public void registerProtocolCertificate() throws Exception {
		EasySSLProtocolSocketFactory easySSLPSFactory = new EasySSLProtocolSocketFactory();
		easySSLPSFactory.setKeyMaterial(createKeyMaterial());
		myProtocolPrefix = (HTTPS_PROTOCOL + uniqueCounter.incrementAndGet());
		Protocol httpsProtocol = new Protocol(myProtocolPrefix,(ProtocolSocketFactory) easySSLPSFactory, port);
		Protocol.registerProtocol(myProtocolPrefix, httpsProtocol);
		log.trace("Protocol [ "+myProtocolPrefix+" ] registered for the first time");
	}	
	
	/**
	 * Load keystore for CLIENT-CERT protected endpoints
	 * 
	 * @return
	 * @throws GeneralSecurityException
	 * @throws Exception
	 */
	private KeyMaterial createKeyMaterial() throws GeneralSecurityException, Exception	{
		KeyMaterial km = null;
		char[] password = keyStorePassphrase.toCharArray();
		File f = new File(keyStoreLocation);
		if (f.exists()) {
			try {
				km = new KeyMaterial(keyStoreLocation, password);
				log.trace("Keystore location is: " + keyStoreLocation + "");
			} catch (GeneralSecurityException gse) {
				if (logErrors){
					log.error("Exception occured while loading keystore from the following location: "+keyStoreLocation, gse);
					throw gse;
				}
			}
		} else {
			log.error("Unable to load Keystore from the following location: " + keyStoreLocation );
			throw new CollectorInitException("Unable to load Keystore from the following location: " + keyStoreLocation);
		}
		return km;
	}
	
	/**
	 * @return the userName
	 */
	@JMXAttribute (name="UserName", description="Displays Username provided for BASIC authetication (if applicable)", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getUserName() {
		return userName;
	}


	/**
	 * @param userName the userName to set
	 */
	public void setUserName(String userName) {
		this.userName = userName;
	}


	/**
	 * @return the password
	 */
	public String getPassword() {
		return password;
	}


	/**
	 * @param password the password to set
	 */
	public void setPassword(String password) {
		this.password = password;
	}

	
	/**
	 * @return the url
	 */
	@JMXAttribute (name="Url", description="Displays URL for target endpoint that needs to be monitored")
	public String getUrl() {
		return this.url.toString();
	}

	/**
	 * @param url to set
	 */
	public void setUrl(String url) {
		try{
			this.url = new URL(url);
			host = this.url.getHost();
			port = this.url.getPort() == -1 ? this.url.getDefaultPort():this.url.getPort();
		}catch(MalformedURLException muex){
			log.error("Incorrect URL format provided to monitor: [ " +this.url+ " ]",muex);
		}
	}


	/**
	 * @return the timeout
	 */
	@JMXAttribute (name="Timeout", description="Displays timeout duration specified for no response from endpoint")
	public int getTimeout() {
		return timeout;
	}

	/**
	 * @param timeout the timeout to set
	 */
	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	/**
	 * @return the successContentMatch
	 */
	@JMXAttribute (name="SuccessContentMatch", description="Content pattern used to derive whether the response should be considered as success")
	public String getSuccessContentMatch() {
		return successContentMatch;
	}

	/**
	 * @param successContentMatch the successContentMatch to set
	 */
	public void setSuccessContentMatch(String successContentMatch) {
		this.successContentMatch = successContentMatch;
		if(successContentMatch!=null && successContentMatch.length()>0){
			successContentPattern = Pattern.compile(successContentMatch.trim());
		}
	}

	/**
	 * @return the failureContentMatch
	 */
	@JMXAttribute (name="FailureContentMatch", description="Content pattern used to derive whether the response should be considered as failure")
	public String getFailureContentMatch() {
		return failureContentMatch;
	}

	/**
	 * @param failureContentMatch the failureContentMatch to set
	 */
	public void setFailureContentMatch(String failureContentMatch) {
		this.failureContentMatch = failureContentMatch;
		if(failureContentMatch!=null && failureContentMatch.length()>0){
			failureContentPattern = Pattern.compile(failureContentMatch.trim());
		}		
	}

	/**
	 * @return the available
	 */
	@JMXAttribute (name="Available", description="Indicates whether the endpoint is available or not", mutability=AttributeMutabilityOption.READ_ONLY)
	public boolean getAvailable() {
		return available;
	}

	/**
	 * @param available the available to set
	 */
	public void setAvailable(boolean available) {
		this.available = available;
	}

	/**
	 * @return String version of Helios URLCollector
	 */
	@JMXAttribute (name="CollectorVersion", description="Version used for URL collector", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getCollectorVersion() {
		return "URLCollector v. " + URL_COLLECTOR_VERSION;
	}	

	/**
	 * @return the authType
	 */
	@JMXAttribute (name="AuthType", description="Indicates whether the AuthType for endpoint is NONE, BASIC, or CLIENT-CERT", mutability=AttributeMutabilityOption.READ_ONLY)
	public AUTH_TYPE getAuthType() {
		return authType;
	}


	/**
	 * @param authType the authType to set
	 */
	public void setAuthType(AUTH_TYPE authType) {
		this.authType = authType;
	}


	/**
	 * @return the host
	 */
	@JMXAttribute (name="Host", description="Hostname of the endpoint", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getHost() {
		return host;
	}


	/**
	 * @return the port
	 */
	@JMXAttribute (name="Port", description="Endpoint Port", mutability=AttributeMutabilityOption.READ_ONLY)
	public int getPort() {
		return port;
	}



	/**
	 * @return the keyStoreLocation
	 */
	@JMXAttribute (name="KeyStoreLocation", description="Keystore location for client certificate", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getKeyStoreLocation() {
		return keyStoreLocation;
	}


	/**
	 * @param keyStoreLocation the keyStoreLocation to set
	 */
	public void setKeyStoreLocation(String keyStoreLocation) {
		this.keyStoreLocation = keyStoreLocation;
	}
	
	/**
	 * @return the keyStorePassphrase
	 */
	@JMXAttribute (name="KeyStorePassphrase", description="Passphrase for client certificate", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getKeyStorePassphrase() {
		return keyStorePassphrase;
	}

	/**
	 * @param keyStorePassphrase the keyStorePassphrase to set
	 */
	public void setKeyStorePassphrase(String keyStorePassphrase) {
		this.keyStorePassphrase = keyStorePassphrase;
	}	
	
	/**
	 * @return the uniqueCounter
	 */
	public int getUniqueCounter() {
		return uniqueCounter.get();
	}

	/**
	 * @return the myProtocolPrefix
	 */
	@JMXAttribute (name="MyProtocolPrefix", description="Custom protocol prefix generated for the CLIENT-CERT protected endpoints", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getMyProtocolPrefix() {
		return myProtocolPrefix;
	}

	/**
	 * Implementation of abstract collectCallback method from base class (AbstractCollector)
	 * 
	 * @return CollectionResult Results of the scheduled URL Monitor
	 */
	public CollectionResult collectCallback(){
		long startTime=System.currentTimeMillis();
		int availability = 0;
		int httpResponseCode=-1;
		int contentSize=-1;
		int successContentMatched=0;
		int failureContentMatched=0;
		BufferedReader reader = null;
		CollectionResult result = new CollectionResult();
		
		try{
			if(httpClient!=null){
				if(!isWebServiceEndpoint){
					httpResponseCode = httpClient.executeMethod(getMethod);
					log.trace("HTTP Response Code returned by URL ["+this.url.toString()+"] is: "+httpResponseCode);
					reader = new BufferedReader(new InputStreamReader(getMethod.getResponseBodyAsStream()));
				} else {
					if(wsStyle.equalsIgnoreCase("SOAP")){
						httpResponseCode = httpClient.executeMethod(postMethod);
						reader = new BufferedReader(new InputStreamReader(postMethod.getResponseBodyAsStream()));
					} else {
						httpResponseCode = httpClient.executeMethod(getMethod);
						reader = new BufferedReader(new InputStreamReader(getMethod.getResponseBodyAsStream()));
					}
				}
				if(httpResponseCode != HttpStatus.SC_OK){
					availability=0;
					throw new CollectorException("HTTP Response Code returned by URL ["+this.url.toString()+"] is: "+httpResponseCode);
				} else { // Response code is 200
					availability=1; //but we now will check for any success or failure patterns
					char[] holder = new char[BYTES_TO_READ];
					String firstBucket = "";
					String secondBucket = "";
					try{ 
						int bytesRead = reader.read(holder,0,BYTES_TO_READ);
						while(bytesRead!=-1){
								secondBucket = new String(holder);
							if(successContentPattern!=null && successContentMatched==0)
								successContentMatched = successContentPattern.matcher(firstBucket+secondBucket).find()==true?1:0;
							
							if(failureContentPattern!=null && failureContentMatched==0)
								failureContentMatched = failureContentPattern.matcher(firstBucket+secondBucket).find()==true?1:0;
							
							contentSize+=bytesRead;
							firstBucket=secondBucket;
							holder = new char[BYTES_TO_READ];
							bytesRead = reader.read(holder,0,BYTES_TO_READ);
						}
					}catch(IOException iox){

					}		
					
					if(failureContentMatched==1) 
						availability = 0;
					else if(successContentPattern!=null && successContentMatched == 0) 
						availability = 0;
					
					result.setResultForLastCollection(CollectionResult.Result.SUCCESSFUL);						
				}									
			} else { // Either HTTPClient or GetMethod is not initialized properly
				availability=0;
				throw new CollectorException("Invalid state of HttpClient or GetMethod for location [ " + getUrl() + " ]");
			}
		} catch(Exception ex){
			if(logErrors){
				log.error(ex.getMessage(),ex);
			}
			result.setResultForLastCollection(CollectionResult.Result.FAILURE);
			result.setAnyException(ex);
			return result;
		} finally{
			try {
				if(reader!=null){
					reader.close();
				}
				tracer.traceSticky(availability, defaultAvailabilityLabel, getTracingNameSpace());
				//tracer.traceSticky(httpResponseCode, "Response Code", getTracingNameSpace());
				//tracer.traceStickyDelta(1, httpResponseCode+"", StringHelper.append(getTracingNameSpace(),true,"Response Codes"));
				tracer.traceIncident(1, httpResponseCode+"", StringHelper.append(getTracingNameSpace(),true,"Response Codes"));
				tracer.traceSticky(contentSize, "Content Size", getTracingNameSpace());
				tracer.traceSticky(System.currentTimeMillis()-startTime, "Elapsed Time", getTracingNameSpace());
				if(successContentPattern != null){
					tracer.traceSticky(successContentMatched, "Success Content Match", getTracingNameSpace());
				}
				if(failureContentPattern != null){
					tracer.traceSticky(failureContentMatched, "Failure Content Match", getTracingNameSpace());
				}				
			}catch(Exception ex){
				reader = null;
			}
		}
		return result;
	}
	
	/**
	 * Parses response returned by endpoint
	 * @param reader
	 * @return
	 */
//	public StringBuilder parseContent(BufferedReader reader){
//		if(reader==null){
//			return null;
//		}
//		StringBuilder tempBuilder = new StringBuilder();
//		try{
//			String oneLine = reader.readLine();
//			while(oneLine!=null){
//				log.trace(oneLine+"\n");
//				tempBuilder.append(oneLine);
//				oneLine=reader.readLine();
//			}
//		}catch(IOException iox){
//			tempBuilder=null;
//		}			
//		return tempBuilder;
//	}
	
	
	/**
	 * Unregisters any custom Protocol set for this instance
	 */
	public void stopCollector(){
		if(myProtocolPrefix!=null){
			Protocol.unregisterProtocol(myProtocolPrefix);
		}
		if(getMethod!=null){
			getMethod.releaseConnection();
		}else if(postMethod!=null){
			postMethod.releaseConnection();
		}
	}

	/**
	 * @return the wsStyle
	 */
	@JMXAttribute (name="WsStyle", description="Indicates whether endpoint supports SOAP or REST style services", mutability=AttributeMutabilityOption.READ_ONLY)
	public String getWsStyle() {
		return wsStyle;
	}

	/**
	 * Constructs a <code>StringBuilder</code> with all attributes
	 * in name = value format.
	 *
	 * @return a <code>String</code> representation 
	 * of this object.
	 */
	public String toString()
	{
	    final String TAB = "    ";
	    StringBuilder retValue = new StringBuilder("");
	    retValue.append("url = " + this.url + TAB);
	    retValue.append("host = " + this.host + TAB);
	    retValue.append("port = " + this.port + TAB);
	    retValue.append("timeout = " + this.timeout + TAB);
	    retValue.append("successContentMatch = " + this.successContentMatch + TAB);
	    retValue.append("failureContentMatch = " + this.failureContentMatch + TAB);
	    retValue.append("available = " + this.available + TAB);
	    retValue.append("authType = " + this.authType + TAB);
	    retValue.append("userName = " + this.userName + TAB);
	    retValue.append("password = " + this.password + TAB);
	    retValue.append("keyStoreLocation = " + this.keyStoreLocation + TAB);
	    retValue.append("keyStorePassphrase = " + this.keyStorePassphrase + TAB);
	    retValue.append("httpClient = " + this.httpClient + TAB);
	    retValue.append("myProtocolPrefix = " + this.myProtocolPrefix + TAB);
	    retValue.append("wsStyle = " + this.wsStyle + TAB);
	    retValue.append("isWebServiceEndpoint = " + this.isWebServiceEndpoint + TAB);
	    retValue.append(" )");
	
	    return retValue.toString();
	}

}
