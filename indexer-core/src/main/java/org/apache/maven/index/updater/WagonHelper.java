package org.apache.maven.index.updater;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0    
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.WagonException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.repository.Repository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * This is a helper for obtaining Wagon based ResourceFetchers. Some Indexer integrations does have access to Wagon
 * already, so this is here just to help them. Since Wagon (et al) is just optional dependency, looking up this
 * component in integrations where Wagon is not present, should be avoided. This helper is rather limited, as it offers
 * only "HTTP" wagons! This is not made a Plexus component since SISU would crack in CLI, while trying to load up this
 * class, because of lacking Wagon classes from classpath!
 *
 * @author cstamas
 */
public class WagonHelper
{
    private final Injector injector;

    public WagonHelper( final Injector injector )
    {
        this.injector = injector;
    }

    public WagonFetcher getWagonResourceFetcher( final TransferListener listener )
    {
        return getWagonResourceFetcher( listener, null, null );
    }

    /**
     * @param listener
     * @param authenticationInfo
     * @param proxyInfo
     * @return
     * @deprecated use getWagonResourceFetcher with protocol argument
     */
    public WagonFetcher getWagonResourceFetcher( final TransferListener listener,
                                                 final AuthenticationInfo authenticationInfo,
                                                 final ProxyInfo proxyInfo )
    {
        // we limit ourselves to HTTP only
        return new WagonFetcher( injector.getInstance( Key.get( Wagon.class, Names.named( "http" ) ) ),
                listener, authenticationInfo, proxyInfo );
    }

    /**
     * @param listener
     * @param authenticationInfo
     * @param proxyInfo
     * @param protocol           protocol supported by wagon http/https
     * @return
     * @since 4.1.3
     */
    public WagonFetcher getWagonResourceFetcher( final TransferListener listener,
                                                 final AuthenticationInfo authenticationInfo,
                                                 final ProxyInfo proxyInfo,
                                                 final String protocol )
    {
        return new WagonFetcher( injector.getInstance( Key.get( Wagon.class, Names.named( protocol ) ) ),
                listener, authenticationInfo, proxyInfo );
    }

    public static class WagonFetcher
        implements ResourceFetcher
    {
        private final TransferListener listener;

        private final AuthenticationInfo authenticationInfo;

        private final ProxyInfo proxyInfo;

        private final Wagon wagon;

        public WagonFetcher( final Wagon wagon, final TransferListener listener,
                             final AuthenticationInfo authenticationInfo, final ProxyInfo proxyInfo )
        {
            this.wagon = wagon;
            this.listener = listener;
            this.authenticationInfo = authenticationInfo;
            this.proxyInfo = proxyInfo;
        }

        public void connect( final String id, final String url )
            throws IOException
        {
            Repository repository = new Repository( id, url );

            try
            {
                // wagon = wagonManager.getWagon( repository );

                if ( listener != null )
                {
                    wagon.addTransferListener( listener );
                }

                // when working in the context of Maven, the WagonManager is already
                // populated with proxy information from the Maven environment

                if ( authenticationInfo != null )
                {
                    if ( proxyInfo != null )
                    {
                        wagon.connect( repository, authenticationInfo, proxyInfo );
                    }
                    else
                    {
                        wagon.connect( repository, authenticationInfo );
                    }
                }
                else
                {
                    if ( proxyInfo != null )
                    {
                        wagon.connect( repository, proxyInfo );
                    }
                    else
                    {
                        wagon.connect( repository );
                    }
                }
            }
            catch ( AuthenticationException ex )
            {
                String msg = "Authentication exception connecting to " + repository;
                logError( msg, ex );
                throw new IOException( msg, ex );
            }
            catch ( WagonException ex )
            {
                String msg = "Wagon exception connecting to " + repository;
                logError( msg, ex );
                throw new IOException( msg, ex );
            }
        }

        public void disconnect()
            throws IOException
        {
            if ( wagon != null )
            {
                try
                {
                    wagon.disconnect();
                }
                catch ( ConnectionException ex )
                {
                    throw new IOException( ex.toString(), ex );
                }
            }
        }

        public InputStream retrieve( String name )
            throws IOException, FileNotFoundException
        {
            final File target = Files.createTempFile( name, "tmp" ).toFile();
            target.deleteOnExit();
            retrieve( name, target );
            return new FileInputStream( target )
            {
                @Override
                public void close()
                    throws IOException
                {
                    super.close();
                    target.delete();
                }
            };
        }

        public void retrieve( final String name, final File targetFile )
            throws IOException, FileNotFoundException
        {
            try
            {
                wagon.get( name, targetFile );
            }
            catch ( AuthorizationException e )
            {
                targetFile.delete();
                String msg = "Authorization exception retrieving " + name;
                logError( msg, e );
                throw new IOException( msg, e );
            }
            catch ( ResourceDoesNotExistException e )
            {
                targetFile.delete();
                String msg = "Resource " + name + " does not exist";
                logError( msg, e );
                FileNotFoundException fileNotFoundException = new FileNotFoundException( msg );
                fileNotFoundException.initCause( e );
                throw fileNotFoundException;
            }
            catch ( WagonException e )
            {
                targetFile.delete();
                String msg = "Transfer for " + name + " failed";
                logError( msg, e );
                throw new IOException( msg + "; " + e.getMessage(), e );
            }
        }

        private void logError( final String msg, final Exception ex )
        {
            if ( listener != null )
            {
                listener.debug( msg + "; " + ex.getMessage() );
            }
        }
    }
}