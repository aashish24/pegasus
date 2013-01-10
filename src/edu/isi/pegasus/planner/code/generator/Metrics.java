/**
 *  Copyright 2007-2008 University Of Southern California
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package edu.isi.pegasus.planner.code.generator;

import edu.isi.pegasus.common.logging.LogManager;
import edu.isi.pegasus.common.logging.LogManagerFactory;

import edu.isi.pegasus.planner.classes.PlannerMetrics;

import edu.isi.pegasus.common.util.Boolean;

import edu.isi.pegasus.planner.classes.PegasusBag;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * Logs workflow metrics to a file in the submit directory and also sends them
 * over a HTTP connection to a Metrics Server.
 *
 *
 * @author Karan Vahi
 * @version $Revision$
 */
public class Metrics  {


    /**
     * The suffix to use while constructing the name of the metrics file
     */
    public static final String METRICS_FILE_SUFFIX = ".metrics";


    /**
     * The default URL for the metrics server to use
     */
    public static final String METRICS_SERVER_DEFAULT_URL = "http://metrics.pegasus.isi.edu/metrics";

    /**
     * The name of the environment variable that sets whether to collect metrics or not
     */
    public static final String COLLECT_METRICS_ENV_VARIABLE = "PEGASUS_METRICS";
    
    /**
     * The name of the environment variable that overrides the default server url
     */
    public static final String METRICS_SERVER_URL_ENV_VARIABLE = "PEGASUS_METRICS_SERVER";

    /**
     * The timeout in seconds for sending the metrics to the server
     */
    public static final int METRICS_SEND_TIMEOUT  = 5;
    
    /**
     * boolean indicating whether to log metrics or not
     */
    private boolean mSendMetricsToServer;

    /**
     * The List of URLS for the metrics servers to report to.
     */
    private List<String> mMetricsServers;

    /**
     * The logger object
     */
    private  LogManager mLogger;


    public Metrics(){
        mSendMetricsToServer = true;
        mMetricsServers    = new LinkedList();
    }

    /**
     * Initializes the object
     *
     * @param bag   bag of pegasus objects
     */
    public void initialize( PegasusBag bag ){
        String value = System.getenv( COLLECT_METRICS_ENV_VARIABLE );
        mSendMetricsToServer =  Boolean.parse(  value, true );

        value = System.getenv( METRICS_SERVER_URL_ENV_VARIABLE );
        if( value != null ){
            String[] urls = value.split( "," );
            for( int i = 0 ; i < urls.length; i++ ){
                mMetricsServers.add( urls[i] );
            }
        }
        else{
            mMetricsServers.add( METRICS_SERVER_DEFAULT_URL );
        }

        //intialize the logger defensively
        if( bag != null ){
            mLogger = bag.getLogger();
        }
        if( mLogger == null ){
            mLogger = LogManagerFactory.loadSingletonInstance();
        }
    }

    /**
     * Logs the metrics to the metrics server and to the submit directory
     *
     * @param metrics
     *
     * @throws IOException
     */
    public void logMetrics( PlannerMetrics metrics ) throws IOException{
        //lets write out to the local file
        this.writeOutMetricsFile( metrics );

        if( this.mSendMetricsToServer ){
            for( String url: mMetricsServers ){
                sendMetricsAsynchronously( metrics , url );
            }
        }
    }


    
    /**
     * Writes out the workflow metrics file in the submit directory
     *
     * @param metrics  the metrics to be written out.
     *
     * @return the path to metrics file in the submit directory
     *
     * @throws IOException in case of error while writing out file.
     */
    private File writeOutMetricsFile( PlannerMetrics metrics ) throws IOException{
        
        if( metrics == null ){
            throw new IOException( "NULL Metrics passed" );
        }
        
        //create a writer to the braindump.txt in the directory.
        File f =  metrics.getMetricsFileLocationInSubmitDirectory();

        if( f == null ){
            throw new IOException( "The metrics file location is not yet initialized" );
        }

        PrintWriter writer =
                  new PrintWriter(new BufferedWriter(new FileWriter(f)));
        
 
        writer.println( metrics.toPrettyJson() );
        writer.write(  "\n" );

        writer.close();
                
        return f;
    }

    /**
     * Sends the planner metrics to the metrics server
     *
     * @param metrics   the metrics to log
     * @param url       the url to send the metrics to
     */
    private void sendMetricsSynchronously(PlannerMetrics metrics, String url ) throws IOException{
        mLogger.log( "Planner Metrics will be sent to " + url,
                     LogManager.DEBUG_MESSAGE_LEVEL );

        SendMetrics sm = new SendMetrics( metrics, url );

        SendMetricsResult result = sm.call();

        if( result.getCode() == 202 ){
            mLogger.log( "Metrics succesfully sent to the server", LogManager.DEBUG_MESSAGE_LEVEL );
        }
        else{
            mLogger.log( "Unable to send metrics to the server " + result,
                             LogManager.DEBUG_MESSAGE_LEVEL );
        }
    }

    /**
     * Sends the planner metrics to the metrics server asynchrnously with a
     * timeout of 5 seconds
     *
     * @param metrics   the metrics to log
     * @param url       the url to send the metrics to
     */
    private void sendMetricsAsynchronously( PlannerMetrics metrics, String url ){

        mLogger.log( "Planner Metrics will be sent to " + url,
                     LogManager.DEBUG_MESSAGE_LEVEL );

        ExecutorService executor = Executors.newSingleThreadExecutor();
  //      Future<SendMetricsResult> future =   (Future<SendMetricsResult>) executor.submit(
 //                                                           new FutureTask<SendMetricsResult>( new SendMetrics( metrics, url ) ));

        Future<SendMetricsResult> future =   (Future<SendMetricsResult>) executor.submit( new SendMetrics( metrics, url ));

        SendMetricsResult result = null;
        try {
            result = future.get( METRICS_SEND_TIMEOUT, TimeUnit.SECONDS )  ;

        } catch (InterruptedException ex) {
            mLogger.log( "Interrupted while sending metrics " + url, ex,
                         LogManager.DEBUG_MESSAGE_LEVEL );
        } catch (ExecutionException ex) {
            mLogger.log( "Exception caught while sending metrics to server " + url, ex,
                         LogManager.DEBUG_MESSAGE_LEVEL );
        } catch (TimeoutException e) {
            mLogger.log( "Sending of metrics to server timed out " + url, e ,
                         LogManager.DEBUG_MESSAGE_LEVEL );
        }
        finally{
            executor.shutdownNow();
        }

        if( result != null ){
            if( result.getCode() == 202 ){
               mLogger.log( "Metrics succesfully sent to the server", LogManager.DEBUG_MESSAGE_LEVEL );
            }
            else{
                mLogger.log( "Unable to send metrics to the server " + result,
                                 LogManager.DEBUG_MESSAGE_LEVEL );
            }
        }

        
    }


}
    

/**
 * A Send metrics class that is used to send metrics to the metrics server
 * using HTTP POST methods
 *
 * @author vahi
 */
class SendMetrics  implements Callable{

        private PlannerMetrics mMetrics;

        private String mURL;

        public SendMetrics( PlannerMetrics metrics, String url ){
            mMetrics = metrics;
            mURL     = url;
        }

        public SendMetricsResult call () throws java.io.IOException {
            SendMetricsResult result = this.send( mMetrics, mURL );
            return result;
        }

        /**
         * Sends the planner metrics to the metrics server
         *
         * @param metrics   the metrics to log
         * @param url       the url to send the metrics to
         */
        private SendMetricsResult send(PlannerMetrics metrics, String url ) throws IOException{
            SendMetricsResult result = new SendMetricsResult();
            
       
            URL u = new URL( url );
            HttpURLConnection connection = (HttpURLConnection) u.openConnection();
            connection.setDoOutput( true );
            //connection.setDoInput( true );
            connection.setRequestMethod( "POST" );

            connection.setRequestProperty( "Content-Type", "application/json");

            try{
                OutputStreamWriter out = new OutputStreamWriter(
                                               connection.getOutputStream());

                try{
                    //String payload = URLEncoder.encode( metrics.toJson(), "UTF-8") ;
                    String payload = metrics.toJson();
                    out.write(  payload );
                }
                finally{
                    out.close();
                }

                result.setCode( connection.getResponseCode() );
                result.setResponseMessage( connection.getResponseMessage() );

                /*
                BufferedReader in = new BufferedReader(
                                        new InputStreamReader(
                                         connection.getInputStream()));
                String result;
                while (( result = in.readLine()) != null) {
                    System.out.println( result );
                }*/
            }
            finally{
                connection.disconnect();
            }

            return result;
        }

}


class SendMetricsResult {

    private int mResponseCode;
    private String mResponseMessage;

    public SendMetricsResult() {
        mResponseCode = -1;
        mResponseMessage = "Results not retrieved yet";
    }

    public SendMetricsResult(int code, String message) {
        mResponseCode = code;
        mResponseMessage = message;
    }

    public void setCode(int code) {
        this.mResponseCode = code;
    }

    public void setResponseMessage(String response) {
        this.mResponseMessage = response;
    }

    public int getCode() {
        return this.mResponseCode;
    }

    public String getResponseMessage() {
        return this.mResponseMessage;
    }

    public String toString(){
        StringBuffer sb = new StringBuffer();

        sb.append(  "code = " ).append( this.mResponseCode ).
           append(  "  " ).append( this.mResponseMessage );

        return sb.toString();
    }
}
