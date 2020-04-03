/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ostrichemulators.prevent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author ryan
 */
public class Worklist {

	private static final Logger LOG = LoggerFactory.getLogger( Worklist.class );
	private static ObjectMapper objmap;

	public static List<WorkItem> open( Path saveloc ) throws IOException {
		List<WorkItem> list = new ArrayList<>();
		if ( null == objmap ) {
			objmap = new ObjectMapper();
			objmap.registerModule( new JavaTimeModule() );
			objmap.configure( SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false );
		}

		File file = saveloc.toFile();
		if ( file.exists() ) {
			WorkItem[] reads = objmap.readValue( saveloc.toFile(), WorkItem[].class );
			list.addAll( Arrays.asList( reads ) );
		}
		return list;
	}

	public static void save( List<WorkItem> items, Path savedloc ) throws IOException {
		objmap.writeValue( savedloc.toFile(), items );
	}

	public static Optional<WorkItem> from( Path p ) {
		File f = p.toFile();

		if ( f.canRead() ) {
			if ( f.isDirectory() ) {
				// check if this directory is WFDB, DWC, or ZL

				// DWC
				File[] inners = f.listFiles( fname -> FilenameUtils.isExtension( fname.getName(), "info" ) );
				if ( inners.length > 0 ) {
					return from( inners[0].toPath() );
				}

				// WFDB
				inners = f.listFiles( fname -> FilenameUtils.isExtension( fname.getName(), "hea" ) );
				if ( inners.length > 0 ) {
					return from( inners[0].toPath() );
				}

				// ZL
				inners = f.listFiles( fname -> FilenameUtils.isExtension( fname.getName(), "gzip" ) );
				if ( inners.length > 0 ) {
					//return Optional.of( new WorkItem( p, DigestUtils.md5Hex( p.toAbsolutePath().toString() ), null, null, null, "zl" ) );
					// ignore checksums for now
					return Optional.of( new WorkItem( p, "", null, null, null, "zl" ) );
				}
			}
			else if ( !FilenameUtils.isExtension( p.getFileName().toString(), "hdf5" ) ) {
				try ( InputStream is = new BufferedInputStream( new FileInputStream( p.toFile() ) ) ) {
					return Optional.of( new WorkItem( p, DigestUtils.md5Hex( is ), null, null, null, null ) );
				}
				catch ( IOException x ) {
					LOG.error( "{}", x );
				}
			}
		}
		return Optional.empty();
	}

	public static List<WorkItem> recursively( Path p ) {
		List<WorkItem> items = new ArrayList<>();
		File f = p.toFile();
		if ( f.canRead() ) {
			if ( f.isDirectory() ) {
				// if we have a DWC, WFDB, or ZL directory, we can't recurse...
				// but if we have anything else, then recurse and add whatever we find.

				// DWC
				File[] inners = f.listFiles( fname -> FilenameUtils.isExtension( fname.getName(), "info" ) );
				if ( inners.length > 0 ) {
					from( inners[0].toPath() ).ifPresent( wi -> items.add( wi ) );
				}
				else {
					// WFDB
					inners = f.listFiles( fname -> FilenameUtils.isExtension( fname.getName(), "hea" ) );
					if ( inners.length > 0 ) {
						from( inners[0].toPath() ).ifPresent( wi -> items.add( wi ) );
					}
					else {
						// ZL
						inners = f.listFiles( fname -> FilenameUtils.isExtension( fname.getName(), "gzip" ) );
						if ( inners.length > 0 ) {
							from( p ).ifPresent( wi -> items.add( wi ) );
						}
						else {
							// f is a directory, so add all files we find there, and recurse
							// into all subdirectories
							for ( File sub : f.listFiles() ) {
								if ( sub.isDirectory() ) {
									items.addAll( recursively( sub.toPath() ) );
								}
								else {
									from( sub.toPath() ).ifPresent( wi -> items.add( wi ) );
								}
							}
						}
					}
				}
			}
			else {
				from( p ).ifPresent( wi -> items.add( wi ) );
			}
		}

		return items;
	}
}
