/*******************************************************************************
 * Copyright (c) 2009 Eucalyptus Systems, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, only version 3 of the License.
 * 
 * 
 * This file is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Please contact Eucalyptus Systems, Inc., 130 Castilian Dr., Goleta, CA 93101
 * USA or visit <http://www.eucalyptus.com/licenses/> if you need additional
 * information or have any questions.
 * 
 * This file may incorporate work covered under the following copyright and
 * permission notice:
 * 
 * Software License Agreement (BSD License)
 * 
 * Copyright (c) 2008, Regents of the University of California All rights
 * reserved.
 * 
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF s * LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE
 * PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL, COPYRIGHTED MATERIAL OR
 * PATENTED MATERIAL IN THIS SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED
 * THE PARTY DISCOVERING IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF
 * CALIFORNIA, SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE
 * REMEDY, WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 * REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED,
 * OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH ANY
 * SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */
package com.eucalyptus.cluster;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.principal.Principals;
import com.eucalyptus.bootstrap.Bootstrap;
import com.eucalyptus.bootstrap.Hosts;
import com.eucalyptus.cloud.CloudMetadata.AvailabilityZoneMetadata;
import com.eucalyptus.cluster.ResourceState.VmTypeAvailability;
import com.eucalyptus.cluster.callback.ClusterCertsCallback;
import com.eucalyptus.cluster.callback.LogDataCallback;
import com.eucalyptus.cluster.callback.NetworkStateCallback;
import com.eucalyptus.cluster.callback.PublicAddressStateCallback;
import com.eucalyptus.cluster.callback.ResourceStateCallback;
import com.eucalyptus.cluster.callback.VmStateCallback;
import com.eucalyptus.component.Component;
import com.eucalyptus.component.ComponentId;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.component.Faults;
import com.eucalyptus.component.Faults.CheckException;
import com.eucalyptus.component.Partition;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.ServiceConfigurations;
import com.eucalyptus.component.ServiceRegistrationException;
import com.eucalyptus.component.ServiceUris;
import com.eucalyptus.component.id.ClusterController;
import com.eucalyptus.component.id.ClusterController.GatherLogService;
import com.eucalyptus.crypto.util.B64;
import com.eucalyptus.crypto.util.PEMFiles;
import com.eucalyptus.empyrean.DescribeServicesResponseType;
import com.eucalyptus.empyrean.DescribeServicesType;
import com.eucalyptus.empyrean.DisableServiceType;
import com.eucalyptus.empyrean.EnableServiceType;
import com.eucalyptus.empyrean.ServiceId;
import com.eucalyptus.empyrean.ServiceStatusType;
import com.eucalyptus.empyrean.ServiceTransitionType;
import com.eucalyptus.empyrean.StartServiceType;
import com.eucalyptus.event.ClockTick;
import com.eucalyptus.event.Event;
import com.eucalyptus.event.EventListener;
import com.eucalyptus.event.Hertz;
import com.eucalyptus.event.ListenerRegistry;
import com.eucalyptus.event.Listeners;
import com.eucalyptus.records.EventRecord;
import com.eucalyptus.records.EventType;
import com.eucalyptus.records.Logs;
import com.eucalyptus.system.Threads;
import com.eucalyptus.util.Callback;
import com.eucalyptus.util.Classes;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.FullName;
import com.eucalyptus.util.HasFullName;
import com.eucalyptus.util.LogUtil;
import com.eucalyptus.util.OwnerFullName;
import com.eucalyptus.util.TypeMappers;
import com.eucalyptus.util.async.AsyncRequests;
import com.eucalyptus.util.async.CheckedListenableFuture;
import com.eucalyptus.util.async.ConnectionException;
import com.eucalyptus.util.async.FailedRequestException;
import com.eucalyptus.util.async.RemoteCallback;
import com.eucalyptus.util.async.SubjectMessageCallback;
import com.eucalyptus.util.async.SubjectRemoteCallbackFactory;
import com.eucalyptus.util.fsm.AbstractTransitionAction;
import com.eucalyptus.util.fsm.Automata;
import com.eucalyptus.util.fsm.HasStateMachine;
import com.eucalyptus.util.fsm.StateMachine;
import com.eucalyptus.util.fsm.StateMachineBuilder;
import com.eucalyptus.util.fsm.TransitionAction;
import com.eucalyptus.util.fsm.Transitions;
import com.eucalyptus.vm.VmInstances;
import com.eucalyptus.vm.VmType;
import com.eucalyptus.vm.VmTypes;
import com.eucalyptus.ws.WebServicesException;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.cloud.NodeInfo;
import edu.ucsb.eucalyptus.msgs.BaseMessage;
import edu.ucsb.eucalyptus.msgs.NodeCertInfo;
import edu.ucsb.eucalyptus.msgs.NodeLogInfo;
import edu.ucsb.eucalyptus.msgs.NodeType;

public class Cluster implements AvailabilityZoneMetadata, HasFullName<Cluster>, EventListener, HasStateMachine<Cluster, Cluster.State, Cluster.Transition> {
  private static Logger                                  LOG            = Logger.getLogger( Cluster.class );
  private final StateMachine<Cluster, State, Transition> stateMachine;
  private final ClusterConfiguration                     configuration;
  private final ConcurrentNavigableMap<String, NodeInfo> nodeMap;
  private final BlockingQueue<Throwable>                 pendingErrors  = new LinkedBlockingDeque<Throwable>( );
  private final ClusterState                             state;
  private final ResourceState                            nodeState;
  private NodeLogInfo                                    lastLog        = new NodeLogInfo( );
  private boolean                                        hasClusterCert = false;
  private boolean                                        hasNodeCert    = false;
  
  enum ZoneRegistration implements Predicate<Cluster> {
    REGISTER {
      @Override
      public boolean apply( final Cluster input ) {
        Clusters.getInstance( ).register( input );
        return true;
      }
    },
    DEREGISTER {
      @Override
      public boolean apply( final Cluster input ) {
        Clusters.getInstance( ).registerDisabled( input );
        return true;
      }
    };
    
  }
  
  private enum ServiceStateDispatch implements Predicate<Cluster>, RemoteCallback<ServiceTransitionType, ServiceTransitionType> {
    STARTED( StartServiceType.class ),
    ENABLED( EnableServiceType.class ) {
      @Override
      public boolean apply( final Cluster input ) {
        try {
          super.apply( input );
          ZoneRegistration.REGISTER.apply( input );
          return true;
        } catch ( final Exception t ) {
          return input.swallowException( t );
        }
      }
    },
    DISABLED( DisableServiceType.class ) {
      @Override
      public boolean apply( final Cluster input ) {
        try {
          super.apply( input );
          ZoneRegistration.DEREGISTER.apply( input );
          return true;
        } catch ( Exception ex ) {
          return false;
        }
      }
    };
    final Class<? extends ServiceTransitionType> msgClass;
    
    private ServiceStateDispatch( Class<? extends ServiceTransitionType> msgClass ) {
      this.msgClass = msgClass;
    }
    
    @Override
    public ServiceTransitionType getRequest( ) {
      return Classes.newInstance( this.msgClass );
    }
    
    @Override
    public void fire( ServiceTransitionType msg ) {
      LOG.debug( this.name( ) + " service: " + msg );
    }
    
    @Override
    public boolean apply( final Cluster input ) {
      if ( Hosts.isCoordinator( ) ) {
        try {
          AsyncRequests.newRequest( this ).sendSync( input.configuration );
          return true;
        } catch ( final Exception t ) {
          return input.swallowException( t );
        }
      } else {
        return true;
      }
    }
    
    @Override
    public void initialize( ServiceTransitionType request ) throws Exception {}
    
    @Override
    public void fireException( Throwable t ) {
      Logs.extreme( ).error( t, t );
    }
  }
  
  enum LogRefresh implements Function<Cluster, TransitionAction<Cluster>> {
    LOGS( LogDataCallback.class ),
    CERTS( ClusterCertsCallback.class );
    Class refresh;
    
    private LogRefresh( final Class refresh ) {
      this.refresh = refresh;
    }
    
    @Override
    public TransitionAction<Cluster> apply( final Cluster cluster ) {
      final SubjectRemoteCallbackFactory<RemoteCallback, Cluster> factory = newSubjectMessageFactory( this.refresh, cluster );
      return new AbstractTransitionAction<Cluster>( ) {
        
        @Override
        public final void leave( final Cluster parent, final Callback.Completion transitionCallback ) {
          try {
            AsyncRequests.newRequest( factory.newInstance( ) ).dispatch( parent.getLogServiceConfiguration( ) ).get( );
            transitionCallback.fire( );
          } catch ( final InterruptedException t ) {
            Thread.currentThread( ).interrupt( );
            transitionCallback.fireException( t );
          } catch ( final Exception t ) {
            if ( !parent.swallowException( t ) ) {
              transitionCallback.fireException( t );
            } else {
              transitionCallback.fire( );
            }
          }
        }
      };
    }
  }
  
  private static class ServiceStateCallback extends SubjectMessageCallback<Cluster, DescribeServicesType, DescribeServicesResponseType> {
    public ServiceStateCallback( ) {
      this.setRequest( new DescribeServicesType( ) );
    }
    
    @Override
    public void fire( DescribeServicesResponseType msg ) {
      List<ServiceStatusType> serviceStatuses = msg.getServiceStatuses( );
      Cluster parent = this.getSubject( );
      LOG.debug( "DescribeServices for " + parent.getFullName( ) );
      if ( serviceStatuses.isEmpty( ) ) {
        throw new NoSuchElementException( "Failed to find service info for cluster: " + parent.getFullName( ) );
      } else {
        ServiceConfiguration config = parent.getConfiguration( );
        for ( ServiceStatusType status : serviceStatuses ) {
          if ( "self".equals( status.getServiceId( ).getName( ) ) ) {
            status.setServiceId( TypeMappers.transform( parent.getConfiguration( ), ServiceId.class ) );
          } 
          if ( config.getName( ).equals( status.getServiceId( ).getName( ) ) ) {
            LOG.debug( "Found service info: " + status );
            Component.State serviceState = Component.State.valueOf( status.getLocalState( ) );
            Component.State localState = parent.getConfiguration( ).lookupState( );
            Component.State proxyState = parent.getStateMachine( ).getState( ).proxyState( );
            CheckException ex = Faults.transformToExceptions( ).apply( status );
            if ( Component.State.NOTREADY.equals( serviceState ) ) {
              throw new IllegalStateException( ex );
            } else if ( Component.State.ENABLED.equals( serviceState ) && Component.State.DISABLED.ordinal( ) >= localState.ordinal( ) ) {
              Cluster.ServiceStateDispatch.DISABLED.apply( parent );
            } else if ( Component.State.DISABLED.equals( serviceState ) && Component.State.ENABLED.equals( localState ) ) {
              Cluster.ServiceStateDispatch.ENABLED.apply( parent );
            } else if ( Component.State.LOADED.equals( serviceState ) && Component.State.NOTREADY.ordinal( ) <= localState.ordinal( ) ) {
              Cluster.ServiceStateDispatch.STARTED.apply( parent );
            } else if ( Component.State.NOTREADY.ordinal( ) < serviceState.ordinal( ) ) {
              parent.clearExceptions( );
            }
            return;
          }
        }
      }
      LOG.error( "Failed to find service info for cluster: " + parent.getFullName( ) + " instead found service status for: "
        + serviceStatuses );
      throw new NoSuchElementException( "Failed to find service info for cluster: " + parent.getFullName( ) );
    }

    @Override
    public void setSubject( Cluster subject ) {
      this.getRequest( ).getServices( ).add( TypeMappers.transform( subject.getConfiguration( ), ServiceId.class ) );
      super.setSubject( subject );
    }
  }
  
  enum Refresh implements Function<Cluster, TransitionAction<Cluster>> {
    RESOURCES( ResourceStateCallback.class ),
    NETWORKS( NetworkStateCallback.class ),
    INSTANCES( VmStateCallback.class ),
    VOLATILE_INSTANCES( VmStateCallback.VmPendingCallback.class ),
    ADDRESSES( PublicAddressStateCallback.class ),
    SERVICEREADY( ServiceStateCallback.class );
    Class refresh;
    
    private Refresh( final Class refresh ) {
      this.refresh = refresh;
    }
    
    @Override
    public TransitionAction<Cluster> apply( final Cluster cluster ) {
      final SubjectRemoteCallbackFactory<RemoteCallback, Cluster> factory = newSubjectMessageFactory( this.refresh, cluster );
      return new AbstractTransitionAction<Cluster>( ) {
        
        @Override
        public final void leave( final Cluster parent, final Callback.Completion transitionCallback ) {
          try {
            AsyncRequests.newRequest( factory.newInstance( ) ).sendSync( parent.getConfiguration( ) );
            transitionCallback.fire( );
          } catch ( final InterruptedException ex ) {
            Thread.currentThread( ).interrupt( );
            Exceptions.trace( ex );
            transitionCallback.fire( );
          } catch ( final Exception t ) {
            if ( !parent.swallowException( t ) ) {
              transitionCallback.fireException( t );
            } else {
              transitionCallback.fire( );
            }
          }
        }
      };
    }
  }
  
  private static final List<Class<? extends Exception>> communicationErrors = Lists.newArrayList( ConnectionException.class, IOException.class,
                                                                                                  WebServicesException.class );
  private static final List<Class<? extends Exception>> executionErrors     = Lists.newArrayList( UndeclaredThrowableException.class,
                                                                                                  ExecutionException.class );
  
  public enum State implements Automata.State<State> {
    BROKEN,
    /** cannot establish initial contact with cluster because of CLC side errors **/
    STOPPED,
    /** Component.State.NOTREADY: cluster unreachable **/
    PENDING,
    /** Component.State.NOTREADY: cluster unreachable **/
    AUTHENTICATING,
    STARTING,
    STARTING_NOTREADY,
    /** Component.State.NOTREADY:enter() **/
    NOTREADY,
    /** Component.State.NOTREADY -> Component.State.DISABLED **/
    DISABLED,
    /** Component.State.DISABLED -> DISABLED: service ready, not current primary **/
    /** Component.State.DISABLED -> Component.State.ENABLED **/
    ENABLING,
    ENABLING_RESOURCES,
    ENABLING_NET,
    ENABLING_VMS,
    ENABLING_ADDRS,
    ENABLING_VMS_PASS_TWO,
    ENABLING_ADDRS_PASS_TWO,
    /** Component.State.ENABLED -> Component.State.ENABLED **/
    ENABLED,
    ENABLED_ADDRS,
    ENABLED_RSC,
    ENABLED_NET,
    ENABLED_VMS,
    ENABLED_SERVICE_CHECK;
    public Component.State proxyState( ) {
      try {
        return Component.State.valueOf( this.name( ) );
      } catch ( final Exception ex ) {
        if ( this.equals( DISABLED ) ) {
          return Component.State.DISABLED;
        } else if ( this.ordinal( ) < DISABLED.ordinal( ) ) {
          return Component.State.NOTREADY;
        } else if ( this.ordinal( ) >= ENABLING.ordinal( ) ) {
          return Component.State.ENABLED;
        } else {
          return Component.State.INITIALIZED;
        }
      }
    }
  }
  
  public enum Transition implements Automata.Transition<Transition> {
    RESTART_BROKEN,
    PRESTART,
    /** pending setup **/
    AUTHENTICATE,
    START,
    START_CHECK,
    STARTING_SERVICES,
    NOTREADYCHECK,
    ENABLE,
    ENABLING_RESOURCES,
    ENABLING_NET,
    ENABLING_VMS,
    ENABLING_ADDRS,
    ENABLING_VMS_PASS_TWO,
    ENABLING_ADDRS_PASS_TWO,
    
    ENABLED,
    ENABLED_ADDRS,
    ENABLED_VMS,
    ENABLED_NET,
    ENABLED_SERVICES,
    ENABLED_RSC,
    
    DISABLE,
    DISABLEDCHECK,
    
    STOP,
    
  }
  
  enum ErrorStateListeners implements Callback<Cluster> {
    FLUSHPENDING {
      @Override
      public void fire( final Cluster t ) {
        LOG.debug( "Clearing error logs for: " + t );
        t.clearExceptions( );
      }
    },
    CHECKPENDING {
      @Override
      public void fire( final Cluster t ) {
        if ( !t.pendingErrors.isEmpty( ) ) {
          Logs.exhaust( ).error( t.pendingErrors );
        }
        LOG.debug( "Clearing error logs for: " + t );
        t.clearExceptions( );
      }
    };
  };
  
  public Cluster( final ClusterConfiguration configuration ) {
    super( );
    this.configuration = configuration;
    this.state = new ClusterState( configuration.getName( ) );
    this.nodeState = new ResourceState( configuration.getName( ) );
    this.nodeMap = new ConcurrentSkipListMap<String, NodeInfo>( );
    this.stateMachine = new StateMachineBuilder<Cluster, State, Transition>( this, State.PENDING ) {
      {
        final TransitionAction<Cluster> noop = Transitions.noop( );
        this.in( Cluster.State.DISABLED ).run( Cluster.ZoneRegistration.DEREGISTER );
        this.in( Cluster.State.NOTREADY ).run( Cluster.ServiceStateDispatch.DISABLED );
        this.in( Cluster.State.ENABLED ).run( Cluster.ZoneRegistration.REGISTER );
        this.from( State.BROKEN ).to( State.PENDING ).error( State.BROKEN ).on( Transition.RESTART_BROKEN ).run( noop );
        
        this.from( State.STOPPED ).to( State.PENDING ).error( State.PENDING ).on( Transition.PRESTART ).run( noop );
        this.from( State.PENDING ).to( State.AUTHENTICATING ).error( State.PENDING ).on( Transition.AUTHENTICATE ).run( LogRefresh.CERTS );
        this.from( State.AUTHENTICATING ).to( State.STARTING ).error( State.PENDING ).on( Transition.START ).run( noop );
        this.from( State.STARTING ).to( State.STARTING_NOTREADY ).error( State.PENDING ).on( Transition.START_CHECK ).run( Refresh.SERVICEREADY );
        this.from( State.STARTING_NOTREADY ).to( State.NOTREADY ).error( State.PENDING ).on( Transition.STARTING_SERVICES ).run( Refresh.SERVICEREADY );
        
        this.from( State.NOTREADY ).to( State.DISABLED ).error( State.NOTREADY ).on( Transition.NOTREADYCHECK ).run( Refresh.SERVICEREADY );
        
        this.from( State.DISABLED ).to( State.DISABLED ).error( State.NOTREADY ).on( Transition.DISABLEDCHECK ).addListener( ErrorStateListeners.FLUSHPENDING ).run( Refresh.SERVICEREADY );
        this.from( State.DISABLED ).to( State.ENABLING ).error( State.DISABLED ).on( Transition.ENABLE ).run( Cluster.ServiceStateDispatch.ENABLED );
        this.from( State.DISABLED ).to( State.STOPPED ).error( State.PENDING ).on( Transition.STOP ).run( noop );
        
        this.from( State.ENABLED ).to( State.DISABLED ).error( State.NOTREADY ).on( Transition.DISABLE ).run( Cluster.ServiceStateDispatch.DISABLED );
        
        this.from( State.ENABLING ).to( State.ENABLING_RESOURCES ).error( State.NOTREADY ).on( Transition.ENABLING_RESOURCES ).run( Refresh.RESOURCES );
        this.from( State.ENABLING_RESOURCES ).to( State.ENABLING_NET ).error( State.NOTREADY ).on( Transition.ENABLING_NET ).run( Refresh.NETWORKS );
        this.from( State.ENABLING_NET ).to( State.ENABLING_VMS ).error( State.NOTREADY ).on( Transition.ENABLING_VMS ).run( Refresh.INSTANCES );
        this.from( State.ENABLING_VMS ).to( State.ENABLING_ADDRS ).error( State.NOTREADY ).on( Transition.ENABLING_ADDRS ).run( Refresh.ADDRESSES );
        this.from( State.ENABLING_ADDRS ).to( State.ENABLING_VMS_PASS_TWO ).error( State.NOTREADY ).on( Transition.ENABLING_VMS_PASS_TWO ).run( Refresh.INSTANCES );
        this.from( State.ENABLING_VMS_PASS_TWO ).to( State.ENABLING_ADDRS_PASS_TWO ).error( State.NOTREADY ).on( Transition.ENABLING_ADDRS_PASS_TWO ).run( Refresh.ADDRESSES );
        this.from( State.ENABLING_ADDRS_PASS_TWO ).to( State.ENABLED ).error( State.NOTREADY ).on( Transition.ENABLING_ADDRS_PASS_TWO ).run( Refresh.ADDRESSES );
        
        this.from( State.ENABLED ).to( State.ENABLED_SERVICE_CHECK ).error( State.NOTREADY ).on( Transition.ENABLED_SERVICES ).run( Refresh.SERVICEREADY );
        this.from( State.ENABLED_SERVICE_CHECK ).to( State.ENABLED_ADDRS ).error( State.NOTREADY ).on( Transition.ENABLED_ADDRS ).run( Refresh.ADDRESSES );
        this.from( State.ENABLED_ADDRS ).to( State.ENABLED_RSC ).error( State.NOTREADY ).on( Transition.ENABLED_RSC ).run( Refresh.RESOURCES );
        this.from( State.ENABLED_RSC ).to( State.ENABLED_NET ).error( State.NOTREADY ).on( Transition.ENABLED_NET ).run( Refresh.NETWORKS );
        this.from( State.ENABLED_NET ).to( State.ENABLED_VMS ).error( State.NOTREADY ).on( Transition.ENABLED_VMS ).run( Refresh.INSTANCES );
        this.from( State.ENABLED_VMS ).to( State.ENABLED ).error( State.NOTREADY ).on( Transition.ENABLED ).run( ErrorStateListeners.FLUSHPENDING );
      }
    }.newAtomicMarkedState( );
  }
  
  public void clearExceptions( ) {
    if ( !this.pendingErrors.isEmpty( ) ) {
      final List<Throwable> currentErrors = Lists.newArrayList( );
      this.pendingErrors.drainTo( currentErrors );
      for ( final Throwable t : currentErrors ) {
        final Throwable filtered = Exceptions.filterStackTrace( t );
        LOG.debug( this.configuration + ": Clearing error: "
                   + filtered.getMessage( ), filtered );
      }
    } else {
      LOG.debug( this.configuration + ": no pending errors to clear." );
    }
  }
  
  private void fireClockTick( final Hertz tick ) {
    try {
      Component.State systemState;
      try {
        systemState = this.configuration.lookupState( );
      } catch ( final NoSuchElementException ex1 ) {
        this.stop( );
        return;
      }
      final boolean initialized = systemState.ordinal( ) > Component.State.LOADED.ordinal( );
      if ( !this.stateMachine.isBusy( ) ) {
        Callable<CheckedListenableFuture<Cluster>> transition = null;
        switch ( this.stateMachine.getState( ) ) {
          case PENDING:
          case AUTHENTICATING:
          case STARTING:
            if ( tick.isAsserted( Clusters.getConfiguration( ).getPendingInterval( ) ) ) {
              transition = startingTransition( );
            }
            break;
          case NOTREADY:
            if ( initialized && tick.isAsserted( Clusters.getConfiguration( ).getNotreadyInterval( ) ) ) {
              transition = notreadyTransition( );
            }
            break;
          case DISABLED:
            if ( initialized && tick.isAsserted( Clusters.getConfiguration( ).getDisabledInterval( ) )
                 && ( Component.State.DISABLED.equals( systemState ) || Component.State.NOTREADY.equals( systemState ) ) ) {
              transition = disabledTransition( );
            } else if ( initialized && tick.isAsserted( Clusters.getConfiguration( ).getDisabledInterval( ) )
                        && Component.State.ENABLED.equals( systemState ) ) {
              transition = enablingTransition( );
            }
            break;
          case ENABLING:
          case ENABLING_RESOURCES:
          case ENABLING_NET:
          case ENABLING_VMS:
          case ENABLING_ADDRS:
          case ENABLING_VMS_PASS_TWO:
          case ENABLING_ADDRS_PASS_TWO:
          case ENABLED:
          case ENABLED_ADDRS:
          case ENABLED_RSC:
          case ENABLED_NET:
          case ENABLED_VMS:
          case ENABLED_SERVICE_CHECK:
            if ( initialized && tick.isAsserted( Clusters.getConfiguration( ).getEnabledInterval( ) )
                 && Component.State.ENABLED.equals( this.configuration.lookupState( ) ) ) {
              transition = enabledTransition( );
            } else if ( initialized && tick.isAsserted( VmInstances.VOLATILE_STATE_INTERVAL_SEC )
                        && Component.State.ENABLED.equals( this.configuration.lookupState( ) ) ) {
              Refresh.VOLATILE_INSTANCES.apply( this );
            } else if ( ( initialized && Component.State.DISABLED.equals( this.configuration.lookupState( ) ) )
                        || Component.State.NOTREADY.equals( this.configuration.lookupState( ) ) ) {
              transition = disableTransition( );
            }
            break;
          default:
            break;
        }
        if ( transition != null ) {
          try {
            transition.call( ).get( 30000L, TimeUnit.MILLISECONDS );
            Cluster.this.clearExceptions( );
          } catch ( final Exception ex ) {
            LOG.error( ex, ex );
          }
        }
      }
    } catch ( final Exception ex ) {
      LOG.error( ex, ex );
    }
  }
  
  private Callable<CheckedListenableFuture<Cluster>> disableTransition( ) {
    Callable<CheckedListenableFuture<Cluster>> transition;
    transition = Automata.sequenceTransitions( this, State.ENABLED, State.DISABLED );
    return transition;
  }
  
  private Callable<CheckedListenableFuture<Cluster>> enabledTransition( ) {
    Callable<CheckedListenableFuture<Cluster>> transition;
    transition = Automata.sequenceTransitions( this, State.ENABLED, State.ENABLED_SERVICE_CHECK, State.ENABLED_ADDRS, State.ENABLED_RSC,
                                               State.ENABLED_NET, State.ENABLED_VMS, State.ENABLED );
    return transition;
  }
  
  private Callable<CheckedListenableFuture<Cluster>> enablingTransition( ) {
    Callable<CheckedListenableFuture<Cluster>> transition;
    transition = Automata.sequenceTransitions( this, State.ENABLING, State.ENABLING_RESOURCES, State.ENABLING_NET, State.ENABLING_VMS,
                                               State.ENABLING_ADDRS, State.ENABLING_VMS_PASS_TWO, State.ENABLING_ADDRS_PASS_TWO, State.ENABLED );
    return transition;
  }
  
  private Callable<CheckedListenableFuture<Cluster>> disabledTransition( ) {
    Callable<CheckedListenableFuture<Cluster>> transition;
    transition = Automata.sequenceTransitions( this, State.DISABLED, State.DISABLED );
    return transition;
  }
  
  private Callable<CheckedListenableFuture<Cluster>> notreadyTransition( ) {
    Callable<CheckedListenableFuture<Cluster>> transition;
    transition = Automata.sequenceTransitions( this, State.NOTREADY, State.DISABLED );
    return transition;
  }
  
  private Callable<CheckedListenableFuture<Cluster>> startingTransition( ) {
    Callable<CheckedListenableFuture<Cluster>> transition;
    transition = Automata.sequenceTransitions( this, State.STOPPED, State.PENDING, State.AUTHENTICATING, State.STARTING,
                                               State.STARTING_NOTREADY,
                                               State.NOTREADY, State.DISABLED );
    return transition;
  }
  
  public Boolean isReady( ) {
    return this.hasClusterCert && this.hasNodeCert
           && Bootstrap.isFinished( );
  }
  
  public X509Certificate getClusterCertificate( ) {
    return Partitions.lookup( this.configuration ).getCertificate( );
  }
  
  public X509Certificate getNodeCertificate( ) {
    return Partitions.lookup( this.configuration ).getNodeCertificate( );
  }
  
  @Override
  public String getName( ) {
    return this.configuration.getName( );
  }
  
  public NavigableSet<String> getNodeTags( ) {
    return this.nodeMap.navigableKeySet( );
  }
  
  public NodeInfo getNode( final String serviceTag ) {
    return this.nodeMap.get( serviceTag );
  }
  
  public void updateNodeInfo( final ArrayList<String> serviceTags ) {
    NodeInfo ret = null;
    
    for ( final String serviceTag : this.nodeMap.keySet( ) ) {
      if ( !serviceTags.contains( serviceTag ) ) {
        this.nodeMap.remove( serviceTag );
      }
    }
    
    for ( final String serviceTag : serviceTags ) {
      if ( ( ret = this.nodeMap.putIfAbsent( serviceTag, new NodeInfo( serviceTag ) ) ) != null ) {
        ret.touch( );
        ret.setServiceTag( serviceTag );
        ret.setIqn( "No IQN reported" );
      }
    }
  }
  
  public void updateNodeInfo( final List<NodeType> nodeTags ) {
    NodeInfo ret = null;
    
    for ( final String serviceTag : this.nodeMap.keySet( ) ) {
      for ( final NodeType node : nodeTags ) {
        if ( !node.getServiceTag( ).equals( serviceTag ) ) {
          this.nodeMap.remove( serviceTag );
        }
      }
    }
    
    for ( final NodeType node : nodeTags ) {
      if ( ( ret = this.nodeMap.putIfAbsent( node.getServiceTag( ), new NodeInfo( node ) ) ) != null ) {
        ret.touch( );
        ret.setServiceTag( node.getServiceTag( ) );
        ret.setIqn( node.getIqn( ) );
      }
    }
  }
  
  @Override
  public int compareTo( final Cluster that ) {
    return this.getName( ).compareTo( that.getName( ) );
  }
  
  public ClusterConfiguration getConfiguration( ) {
    return this.configuration;
  }
  
  public ClusterState getState( ) {
    return this.state;
  }
  
  public ResourceState getNodeState( ) {
    return this.nodeState;
  }
  
  public void start( ) throws ServiceRegistrationException {
    try {
      Clusters.getInstance( ).registerDisabled( this );
      if ( !State.DISABLED.equals( this.stateMachine.getState( ) ) ) {
        final Callable<CheckedListenableFuture<Cluster>> trans = Automata.sequenceTransitions( this,
                                                                                               State.PENDING,
                                                                                               State.AUTHENTICATING,
                                                                                               State.STARTING,
                                                                                               State.STARTING_NOTREADY,
                                                                                               State.NOTREADY,
                                                                                               State.DISABLED );
        Exception lastEx = null;
        for ( int i = 0; i < Clusters.getConfiguration( ).getStartupSyncRetries( ); i++ ) {
          try {
            trans.call( ).get( );
            lastEx = null;
            break;
          } catch ( final InterruptedException ex ) {
            Thread.currentThread( ).interrupt( );
          } catch ( final ServiceRegistrationException ex ) {
            lastEx = ex;
            Logs.exhaust( ).debug( ex, ex );
          } catch ( final Exception ex ) {
            lastEx = ex;
            Logs.exhaust( ).debug( ex, ex );
          }
        }
        Listeners.register( Hertz.class, this );
      }
    } catch ( final NoSuchElementException ex ) {
      Logs.exhaust( ).debug( ex, ex );
      throw ex;
    } catch ( final Exception ex ) {
      Logs.exhaust( ).debug( ex, ex );
      throw new ServiceRegistrationException( "Failed to call start() on cluster " + this.configuration
                                              + " because of: "
                                              + ex.getMessage( ), ex );
    }
  }
  
  public void enable( ) throws ServiceRegistrationException {
    if ( State.ENABLING.ordinal( ) > this.stateMachine.getState( ).ordinal( ) ) {
      try {
        final CheckedListenableFuture<Cluster> result = Automata.sequenceTransitions( this, State.PENDING, State.AUTHENTICATING, State.STARTING,
                                                                                      State.STARTING_NOTREADY, State.NOTREADY,
                                                                                      State.DISABLED, State.ENABLING, State.ENABLING_RESOURCES,
                                                                                      State.ENABLING_NET, State.ENABLING_VMS,
                                                                                      State.ENABLING_ADDRS, State.ENABLING_VMS_PASS_TWO,
                                                                                      State.ENABLING_ADDRS_PASS_TWO, State.ENABLED ).call( );
        RuntimeException fail = null;
        for ( int i = 0; i < Clusters.getConfiguration( ).getStartupSyncRetries( ); i++ ) {
          try {
            result.get( );
            fail = null;
            break;
          } catch ( Exception ex ) {
            try {
              TimeUnit.SECONDS.sleep( 1 );
            } catch ( Exception ex1 ) {
              LOG.error( ex1 , ex1 );
            }
            fail = Exceptions.toUndeclared( ex );
          }
        }
        if ( fail != null ) {
          throw fail;
        }
      } catch ( final InterruptedException ex ) {
        Thread.currentThread( ).interrupt( );
      } catch ( final Exception ex ) {
        Logs.exhaust( ).debug( ex, ex );
        throw new ServiceRegistrationException( "Failed to call enable() on cluster " + this.configuration
                                                + " because of: "
                                                + ex.getMessage( ), ex );
      }
    }
  }
  
  public void disable( ) throws ServiceRegistrationException {
    try {
      if ( State.NOTREADY.equals( this.getStateMachine( ).getState( ) ) ) {
        Automata.sequenceTransitions( this, State.NOTREADY, State.DISABLED ).call( ).get( );
      } else if ( State.ENABLED.equals( this.getStateMachine( ).getState( ) ) ) {
        Automata.sequenceTransitions( this, State.ENABLED, State.DISABLED ).call( ).get( );
      }
    } catch ( final InterruptedException ex ) {
      Thread.currentThread( ).interrupt( );
    } catch ( final Exception ex ) {
      Logs.exhaust( ).debug( ex, ex );
      throw new ServiceRegistrationException( "Failed to call disable() on cluster " + this.configuration
                                              + " because of: "
                                              + ex.getMessage( ), ex );
    }
  }
  
  public void stop( ) throws ServiceRegistrationException {
    try {
      Automata.sequenceTransitions( this, State.DISABLED, State.STOPPED ).call( ).get( );
    } catch ( final InterruptedException ex ) {
      Thread.currentThread( ).interrupt( );
    } catch ( final Exception ex ) {
      Logs.exhaust( ).debug( ex, ex );
      throw new ServiceRegistrationException( "Failed to call stop() on cluster " + this.configuration
                                              + " because of: "
                                              + ex.getMessage( ), ex );
    } finally {
      ListenerRegistry.getInstance( ).deregister( Hertz.class, this );
      ListenerRegistry.getInstance( ).deregister( ClockTick.class, this );
      Clusters.getInstance( ).deregister( this.getName( ) );
    }
  }
  
  @Override
  public int hashCode( ) {
    final int prime = 31;
    int result = 1;
    result = prime * result
             + ( ( this.configuration == null )
               ? 0
               : this.configuration.hashCode( ) );
    result = prime * result
             + ( ( this.state == null )
               ? 0
               : this.state.hashCode( ) );
    return result;
  }
  
  @Override
  public boolean equals( final Object obj ) {
    if ( this == obj ) {
      return true;
    }
    if ( obj == null ) {
      return false;
    }
    if ( this.getClass( ) != obj.getClass( ) ) {
      return false;
    }
    final Cluster other = ( Cluster ) obj;
    if ( this.configuration == null ) {
      if ( other.configuration != null ) {
        return false;
      }
    } else if ( !this.configuration.equals( other.configuration ) ) {
      return false;
    }
    if ( this.state == null ) {
      if ( other.state != null ) {
        return false;
      }
    } else if ( !this.state.equals( other.state ) ) {
      return false;
    }
    return true;
  }
  
  public URI getUri( ) {
    return ServiceUris.remote( this.configuration );
  }
  
  public String getHostName( ) {
    return this.configuration.getHostName( );
  }
  
  public String getInsecureServicePath( ) {
    return this.configuration.getInsecureServicePath( );
  }
  
  public Integer getPort( ) {
    return this.configuration.getPort( );
  }
  
  public String getServicePath( ) {
    return this.configuration.getServicePath( );
  }
  
  public ThreadFactory getThreadFactory( ) {
    return Threads.lookup( ClusterController.class, Cluster.class, this.getFullName( ).toString( ) );
  }
  
  @Override
  public String toString( ) {
    final StringBuilder buf = new StringBuilder( );
    buf.append( "Cluster " ).append( this.configuration ).append( '\n' );
    buf.append( "Cluster " ).append( this.configuration.getName( ) );//.append( " mq=" ).append( this.getConfiguration( ).lookupService( ). ).append( '\n' );//TODO:GRZE:RESTORE ME
    for ( final NodeInfo node : this.nodeMap.values( ) ) {
      buf.append( "Cluster " ).append( this.configuration.getName( ) ).append( " node=" ).append( node ).append( '\n' );
    }
    for ( final VmType type : VmTypes.list( ) ) {
      final VmTypeAvailability avail = this.nodeState.getAvailability( type.getName( ) );
      buf.append( "Cluster " ).append( this.configuration.getName( ) ).append( " node=" ).append( avail ).append( '\n' );
    }
    return buf.toString( );
  }
  
  private final AtomicBoolean logUpdate = new AtomicBoolean( false );
  
  public NodeLogInfo getLastLog( ) {
    if ( this.logUpdate.compareAndSet( false, true ) ) {
      final Cluster self = this;
      try {
        /**
         * TODO:ASAP:GRZE: RESTORE
         * Callbacks.newRequest( new LogDataCallback( this, null ) )
         * .execute( this.getServiceEndpoint( ),
         * com.eucalyptus.component.id.Cluster.getLogClientPipeline( ) )
         * .getResponse( ).get( );
         * Callbacks.newLogRequest( new LogDataCallback( this, null ) ).dispatch(
         * this.getServiceEndpoint( ) );
         **/
      } catch ( final Throwable t ) {
        LOG.error( t, t );
      } finally {
        this.logUpdate.set( false );
      }
    }
    return this.lastLog;
  }
  
  public void clearLogPending( ) {
    this.logUpdate.set( false );
  }
  
  public NodeLogInfo getNodeLog( final String nodeIp ) {
    final NodeInfo nodeInfo = Iterables.find( this.nodeMap.values( ), new Predicate<NodeInfo>( ) {
      @Override
      public boolean apply( final NodeInfo arg0 ) {
        return nodeIp.equals( arg0.getName( ) );
      }
    } );
    if ( nodeInfo == null ) {
      throw new NoSuchElementException( "Error obtaining node log files for: " + nodeIp );
    }
    if ( this.logUpdate.compareAndSet( false, true ) ) {
      final Cluster self = this;
      try {
        /**
         * TODO:ASAP:GRZE: RESTORE
         * Callbacks.newRequest( new LogDataCallback( this, null ) )
         * .execute( this.getServiceEndpoint( ),
         * com.eucalyptus.component.id.Cluster.getLogClientPipeline( ) )
         * .getResponse( ).get( );
         **/
//        Callbacks.newLogRequest( new LogDataCallback( this, nodeInfo ) ).dispatch( this.getServiceEndpoint( ) );
      } catch ( final Throwable t ) {
        LOG.debug( t, t );
      } finally {
        this.logUpdate.set( false );
      }
    }
    return nodeInfo.getLogs( );
  }
  
  public void setLastLog( final NodeLogInfo lastLog ) {
    this.lastLog = lastLog;
  }
  
  public boolean checkCerts( final NodeCertInfo certs ) {
    if ( ( certs == null ) || ( certs.getCcCert( ) == null )
         || ( certs.getNcCert( ) == null ) ) {
      return false;
    }
    
    final X509Certificate clusterx509 = PEMFiles.getCert( B64.standard.dec( certs.getCcCert( ) ) );
    final X509Certificate nodex509 = PEMFiles.getCert( B64.standard.dec( certs.getNcCert( ) ) );
    if ( "self".equals( certs.getServiceTag( ) ) || ( certs.getServiceTag( ) == null ) ) {
      return ( this.hasClusterCert = this.checkCerts( this.getClusterCertificate( ), clusterx509 ) )
             && ( this.hasNodeCert = this.checkCerts( this.getNodeCertificate( ), nodex509 ) );
    } else if ( this.nodeMap.containsKey( certs.getServiceTag( ) ) ) {
      final NodeInfo nodeInfo = this.nodeMap.get( certs.getServiceTag( ) );
      nodeInfo.setHasClusterCert( this.checkCerts( this.getClusterCertificate( ), clusterx509 ) );
      nodeInfo.setHasNodeCert( this.checkCerts( this.getNodeCertificate( ), nodex509 ) );
      return nodeInfo.getHasClusterCert( ) && nodeInfo.getHasNodeCert( );
    } else {
      LOG.error( "Cluster " + this.getName( )
                 + " failed to find cluster/node info for service tag: "
                 + certs.getServiceTag( ) );
      return false;
    }
  }
  
  private boolean checkCerts( final X509Certificate realx509, final X509Certificate msgx509 ) {
    if ( realx509 != null ) {
      final Boolean match = realx509.equals( msgx509 );
      EventRecord.here( Cluster.class, EventType.CLUSTER_CERT, this.getName( ), realx509.getSubjectX500Principal( ).getName( ), match.toString( ) ).info( );
      if ( !match ) {
        LOG.warn( LogUtil.subheader( "EXPECTED CERTIFICATE" ) + realx509 );
        LOG.warn( LogUtil.subheader( "RECEIVED CERTIFICATE" ) + msgx509 );
      }
      return match;
    } else {
      return false;
    }
  }
  
  private AbstractTransitionAction<Cluster> newLogRefresh( final Class msgClass ) {//TODO:GRZE:REMOVE
    final Cluster cluster = this;
    final SubjectRemoteCallbackFactory<RemoteCallback, Cluster> factory = newSubjectMessageFactory( msgClass, cluster );
    return new AbstractTransitionAction<Cluster>( ) {
      
      @Override
      public final void leave( final Cluster parent, final Callback.Completion transitionCallback ) {
        try {
          AsyncRequests.newRequest( factory.newInstance( ) ).then( transitionCallback )
                       .sendSync( parent.getLogServiceConfiguration( ) );
        } catch ( final ExecutionException e ) {
          if ( e.getCause( ) instanceof FailedRequestException ) {
            LOG.error( e.getCause( ).getMessage( ) );
          } else if ( ( e.getCause( ) instanceof ConnectionException ) || ( e.getCause( ) instanceof IOException ) ) {
            LOG.error( parent.getName( ) + ": Error communicating with cluster: "
                       + e.getCause( ).getMessage( ) );
          } else {
            LOG.error( e, e );
          }
        } catch ( final InterruptedException e ) {
          LOG.error( e, e );
        }
      }
    };
  }
  
  protected ServiceConfiguration getLogServiceConfiguration( ) {
    final ComponentId glId = ComponentIds.lookup( GatherLogService.class );
    final ServiceConfiguration conf = this.getConfiguration( );
    final URI glUri = ServiceUris.remote( GatherLogService.class, conf.getInetAddress( ) );
    return ServiceConfigurations.createEphemeral( glId, conf.getPartition( ), conf.getName( ), glUri );
  }
  
  @Override
  public void fireEvent( final Event event ) {
    if ( !Bootstrap.isFinished( ) ) {
      LOG.info( this.getFullName( ) + " skipping clock event because bootstrap isn't finished" );
    } else if ( event instanceof Hertz ) {
      this.fireClockTick( ( Hertz ) event );
    }
  }
  
  private static <P, T extends SubjectMessageCallback<P, Q, R>, Q extends BaseMessage, R extends BaseMessage> SubjectRemoteCallbackFactory<T, P> newSubjectMessageFactory( final Class<T> callbackClass, final P subject ) {
    return new SubjectRemoteCallbackFactory( ) {
      @Override
      public T newInstance( ) {
        try {
          final T callback = callbackClass.newInstance( );
          callback.setSubject( subject );
          return callback;
        } catch ( final Throwable t ) {
          LOG.error( t, t );
          throw new RuntimeException( t );
        }
      }
      
      @Override
      public P getSubject( ) {
        return subject;
      }
    };
  }
  
  private <T extends Throwable> boolean swallowException( final T t ) {
    Throwable fin = t;
    if ( t instanceof ExecutionException ) {
      fin = t.getCause( ) != null
        ? t.getCause( )
        : t;
    }
    LOG.error( t );
    if ( Exceptions.isCausedBy( t, InterruptedException.class ) ) {
      Thread.currentThread( ).interrupt( );
    } else if ( Exceptions.isCausedBy( t, FailedRequestException.class ) ) {
      Logs.extreme( ).debug( fin, fin );
      this.pendingErrors.add( fin );
    } else if ( Exceptions.isCausedBy( t, ConnectionException.class ) || Exceptions.isCausedBy( t, IOException.class ) ) {
      LOG.error( this.getName( ) + ": Error communicating with cluster: "
                 + fin.getMessage( ) );
      Logs.extreme( ).debug( fin, fin );
      this.pendingErrors.add( fin );
    } else {
      Logs.extreme( ).debug( fin, fin );
      this.pendingErrors.add( fin );
    }
    return false;
  }
  
  public void check( ) throws Faults.CheckException, IllegalStateException {
    final Cluster.State currentState = this.stateMachine.getState( );
    final Component.State externalState = this.configuration.lookupState( );
    final List<Throwable> currentErrors = Lists.newArrayList( );
    try {
      Refresh.SERVICEREADY.apply( this );
    } catch ( Exception ex ) {
      throw Faults.failure( this.configuration, ex );
    }
    currentErrors.addAll( this.pendingErrors );
    if ( !currentErrors.isEmpty( ) ) {
      throw Faults.failure( this.configuration, currentErrors );
    } else if ( ( currentState.ordinal( ) < State.DISABLED.ordinal( ) )
                || ( Component.State.ENABLED.equals( externalState ) && ( Cluster.State.ENABLING.ordinal( ) >= currentState.ordinal( ) ) ) ) {
      final IllegalStateException ex = new IllegalStateException( "Cluster is currently reported as " + externalState
                                                                  + " but is really "
                                                                  + currentState
                                                                  + ":  please see logs for additional information." );
      this.pendingErrors.add( ex );
      throw Faults.failure( this.configuration, currentErrors );
    }
  }
  
  @Override
  public String getPartition( ) {
    return this.configuration.getPartition( );
  }
  
  public Partition lookupPartition( ) {
    return Partitions.lookup( this.getConfiguration( ) );
  }
  
  @Override
  public FullName getFullName( ) {
    return this.configuration.getFullName( );
  }
  
  @Override
  public StateMachine<Cluster, State, Transition> getStateMachine( ) {
    return this.stateMachine;
  }
  
  @Override
  public String getDisplayName( ) {
    return this.getName( );
  }
  
  @Override
  public OwnerFullName getOwner( ) {
    return Principals.systemFullName( );
  }
  
}
