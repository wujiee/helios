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
package org.helios.collectors.jmx;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.Query;
import javax.management.QueryExp;

import org.apache.log4j.Logger;
import org.helios.collectors.jmx.connection.ConnectionVerifier;
import org.helios.helpers.JMXHelper;
import org.helios.jmx.dynamic.ManagedObjectDynamicMBean;
import org.helios.jmx.dynamic.annotations.JMXManagedObject;
import org.helios.collectors.scheduler.HeliosScheduler;
import org.helios.jmx.threadservices.scheduling.ScheduledTaskHandle;

/**
 * <p>Title: JMXConnectionFactoryMonitor</p>
 * <p>Description: A scheduled back-end job to monitors JMX connection factories  </p> 
 * <p>Company: Helios Development Group</p>
 * @author Sandeep Malhotra (smalhotra@heliosdev.org)
 */
@JMXManagedObject (declared=false, annotated=true)
public class JMXConnectionFactoryMonitor extends ManagedObjectDynamicMBean implements Callable<String> {

	private static final long serialVersionUID = 2154919430590948702L;

	protected Logger log = Logger.getLogger(getClass());
	
	/** The frequency to monitor in milliseconds*/
	protected long frequency = 10000L;
	
	/** Reference to Helios MBeanServer */
	protected MBeanServer heliosJMX = null;
	
	/** Helios Scheduler to trigger this class on specified frequency */
	protected HeliosScheduler hScheduler = null;
	
	/** Handle to a scheduled frequency task */
	protected ScheduledTaskHandle<String> frequencyTaskHandle = null;
	
	/** Object name used to query all MBeans listed as remote JMX servers that Helios is currently monitoring*/
	protected ObjectName jmxConnectionFactories = null;
	
	/** ExecutorService used to monitor multiple remote MBeanServers concurrently */
	protected ExecutorService executor = null;
	
	/** Default thread pool size */
	protected int threadPoolSize = 5;
	
	protected QueryExp expression= null;

	/**
	 * default init method to get hold of reference to Helios MBean Server and Scheduler.  It
	 * also schedules the monitor with HeliosScheduler.  
	 * @throws NullPointerException 
	 * @throws MalformedObjectNameException 
	 * 
	 */
	public void start() throws MalformedObjectNameException, NullPointerException{
		heliosJMX = JMXHelper.getHeliosMBeanServer();
		hScheduler = HeliosScheduler.getInstance();
		executor = Executors.newFixedThreadPool(threadPoolSize);
		expression = Query.not(new ObjectName("*:*,domain=Helios"));
		try{
			jmxConnectionFactories = new ObjectName("org.helios.jmx.mbeanservers:*");
			frequencyTaskHandle = hScheduler.scheduleAtFrequency(false, this, 0L, frequency, TimeUnit.MILLISECONDS);
			log.info("Scheduled JMX Connection Monitor to run every " +frequency+ " milliseconds." );
		}catch(Exception ex){
			log.error("An error occured during the execution of start method in JMXConnectionFactoryMonitor", ex);
		}
	}
	
	
	/**
	 * Method that will be called by HeliosScheduler at specified frequency
	 */
	@SuppressWarnings("unchecked")
	public String call() {
		log.debug("Call method invoked for JMXConnectionFactoryMonitor");
		ObjectName remoteObjectName = null;
		Set<ObjectInstance> remoteJMXConnections = (Set<ObjectInstance>)heliosJMX.queryMBeans(jmxConnectionFactories, expression);
		for(ObjectInstance on: remoteJMXConnections){
			remoteObjectName = on.getObjectName();
			executor.execute(new ConnectionVerifier(remoteObjectName));
		}
		return "";
	}

	/**
	 * @return the threadPoolSize
	 */
	public int getThreadPoolSize() {
		return threadPoolSize;
	}

	/**
	 * @param threadPoolSize the threadPoolSize to set
	 */
	public void setThreadPoolSize(int threadPoolSize) {
		this.threadPoolSize = threadPoolSize;
	}

	/**
	 * @return the frequency
	 */
	public long getFrequency() {
		return frequency;
	}

	/**
	 * @param frequency the frequency to set
	 */
	public void setFrequency(long frequency) {
		this.frequency = frequency;
	}

	
}
