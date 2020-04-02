/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ostrichemulators.prevent;

import com.amihaiemil.docker.Container;
import com.amihaiemil.docker.Docker;
import com.amihaiemil.docker.Image;
import com.amihaiemil.docker.Images;
import com.amihaiemil.docker.LocalDocker;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author ryan
 */
public class DockerManager {

	private static final Logger LOG = LoggerFactory.getLogger( DockerManager.class );
	private static final int MAX_RUNNING_CONTAINERS = 1;

	final Docker docker;
	private Image image;
	private final Deque<WorkItem> todo = new ArrayDeque<>();
	private final ExecutorService executor = Executors.newFixedThreadPool( MAX_RUNNING_CONTAINERS );

	private DockerManager( Docker d ) {
		docker = d;
	}

	public static DockerManager connect() {
		return new DockerManager( SystemUtils.IS_OS_WINDOWS
				? new LocalDocker( new File( "/var/run/docker.sock" ) ) // don't know what the Windows equivalent is yet
				: new LocalDocker( new File( "/var/run/docker.sock" ) ) );
	}

	public boolean verifyOrPrepare() {
		try {
			if ( docker.ping() ) {
				image = null;

				Map<String, Iterable<String>> filters = new HashMap<>();
				filters.put( "reference", List.of( "ry99/prevent" ) );

				Images images = docker.images().filter( filters );
				if ( images.iterator().hasNext() ) {
					image = images.iterator().next();
				}
				else {
					LOG.debug( "PreVent image not found...fetching latest" );
					image = images.pull( "ry99/prevent", "latest" );
				}
			}
		}
		catch ( IOException x ) {
			LOG.error( "Is the Docker service running?", x );
		}
		return ( null != image );
	}

	public Map<Container, String> getContainerStates() {
		Map<Container, String> containers = new HashMap<>();

		Map<String, Iterable<String>> f = new HashMap<>();
		f.put( "ancestor", List.of( "ry99/prevent" ) );
		//f.put( "status", List.of( "running" ) );
		docker.containers().all().forEachRemaining( c -> {
			try {
				JsonObject data = c.inspect().getJsonObject( "State" );
				String status = data.getString( "Status" );
				containers.put( c, status );
			}
			catch ( IOException x ) {
				LOG.warn( "Unable to inspect container: {}", c.containerId(), x );
			}
		} );

		return containers;
	}

	public void shutdown() {
		executor.shutdown();
		try {
			if ( !executor.awaitTermination( 5, TimeUnit.SECONDS ) ) {
				executor.shutdownNow();
			}
		}
		catch ( InterruptedException e ) {
			executor.shutdownNow();
		}
	}

	public synchronized void convert( Collection<WorkItem> work ) throws IOException {
		todo.addAll( work );

		for ( WorkItem item : work ) {
			executor.submit( () -> {
				try {
					Container c = createContainer( item );
					item.started( c.containerId() );
					c.start();
					LOG.debug( "container {} has been started for {}", c.containerId(), item.getFile() );
				}
				catch ( IOException x ) {
					LOG.error( "{}", x );
				}
			} );
		}
	}

	private Container createContainer( WorkItem item ) throws IOException {
		JsonArrayBuilder mounts = Json.createArrayBuilder();
		JsonObjectBuilder volumes = Json.createObjectBuilder();
		mounts.add( String.format( "%s:/opt/todo", item.getFile().getParent().toString() ) );
		volumes.add( "/opt/todo", Json.createObjectBuilder() );

		JsonObjectBuilder hostconfig = Json.createObjectBuilder();
		hostconfig.add( "Binds", mounts );

		JsonObjectBuilder config = Json.createObjectBuilder();
		config.add( "Image", "ry99/prevent" );
		//config.add( "Volumes", volumes );
		config.add( "HostConfig", hostconfig );
		config.add( "Cmd", Json.createArrayBuilder( List.of(
				"--to",
				"hdf5",
				"--localtime",
				String.format( "/opt/todo/%s", item.getFile().getFileName() ) ) ) );

		JsonObject json = config.build();
		LOG.debug( "{}", json );
		Container cc = docker.containers().create( json );
		return cc;
	}
}
