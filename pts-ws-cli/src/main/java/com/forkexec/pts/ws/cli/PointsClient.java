package com.forkexec.pts.ws.cli;

import static javax.xml.ws.BindingProvider.ENDPOINT_ADDRESS_PROPERTY;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;

import javax.xml.ws.Response;
import javax.xml.ws.BindingProvider;

import com.forkexec.pts.ws.*;

import com.forkexec.pts.ws.cli.exception.*;

import pt.ulisboa.tecnico.sdis.ws.uddi.UDDINaming;

/**
 * Client port wrapper.
 *
 * Adds easier end point address configuration to the Port generated by
 * wsimport.
 */
public class PointsClient {

  /** WS port (port type is the interface, port is the implementation) */
  PointsPortType port = null;

  ArrayList<PointsPortType> ports = new ArrayList<>();

  /** Quorum*/
  private int Q = 0;

  /** cache to store points and tag associated with each user */
  private Map<String, Value> cache = new ConcurrentHashMap<>();

  /** WS end point address */
  private Collection<String> wsURLs;

  public Collection<String> getWsURLs() {
    return wsURLs;
  }

  /** output option **/
  private boolean verbose = false;

  public boolean isVerbose() {
    return verbose;
  }

  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }

  /** constructor with provided web service URL */
  public PointsClient(Collection<String> wsURLs) {
    this.wsURLs = wsURLs;
    this.Q = wsURLs.size() / 2 + 1;
    createStubs();
  }

  /** Stub creation and configuration */
  private void createStubs() {
    for (String wsURL : getWsURLs()) {
      if (verbose)
        System.out.println("Creating stub ...");
      PointsService service = new PointsService();
      PointsPortType port = service.getPointsPort();

      if (wsURL != null) {
        if (verbose)
          System.out.println("Setting endpoint address ...");
        BindingProvider bindingProvider = (BindingProvider) port;
        Map<String, Object> requestContext = bindingProvider.getRequestContext();
        requestContext.put(ENDPOINT_ADDRESS_PROPERTY, wsURL);
        ports.add(port);
      }
    }
  }

  /** Email address validation. */
  private void checkValidEmail(final String emailAddress) throws InvalidEmailFault_Exception {
    final String message;
    if (emailAddress == null) {
      message = "Null email is not valid";
    } else if (!Pattern.matches("(\\w\\.?)*\\w+@\\w+(\\.?\\w)*", emailAddress)) {
      message = String.format("Email: %s is not valid", emailAddress);
    } else {
      return;
    }
    throw new InvalidEmailFault_Exception(message, null);
  }

  private Value getMaxValue(String userEmail) throws InvalidEmailFault_Exception {
    ArrayList<Response<ReadResponse>> responses = new ArrayList<>(ports.size());
    ArrayList<Value> quorum = new ArrayList<>(this.Q);
    int n = 0;

    try {
      checkValidEmail(userEmail);

      for (int i = 0; i < ports.size(); i++) {
        port = ports.get(i);
        responses.add(readAsync(userEmail));
      }

      while (n < this.Q) {
        for (int i = 0; i < responses.size(); i++) {
          if (responses.get(i).isDone()) {
            quorum.add(responses.get(i).get().getReturn());
            responses.remove(i);
            n++;
            break;
          }
        }
      }

      Value maxValue = quorum.get(0);
      for (int i = 1; i < this.Q; i++) {
        Value value = quorum.get(i);

        if (value.getTag().getSeq() > maxValue.getTag().getSeq())
          maxValue = value;
      }

      return maxValue;

    } catch (InvalidEmailFault_Exception e) {
      throw new InvalidEmailFault_Exception(e.getMessage(), e.getFaultInfo());
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException("Exception: " + e.getCause());
    }
  }

  private void setMaxValue(String userEmail, Value value)
          throws InvalidEmailFault_Exception, InvalidPointsFault_Exception {
    ArrayList<Response<WriteResponse>> responses = new ArrayList<>(ports.size());
    int n = 0;

    try {
      checkValidEmail(userEmail);

      for (int i = 0; i < ports.size(); i++) {
        port = ports.get(i);
        responses.add(writeAsync(userEmail, value.getVal(), value.getTag()));
      }

      while (n < this.Q) {
        for (int i = 0; i < responses.size(); i++) {
          if (responses.get(i).isDone()) {
            responses.remove(i);
            n++; break;
          }
        }
      }

    }  catch (InvalidEmailFault_Exception e) {
      throw new InvalidEmailFault_Exception(e.getMessage(), e.getFaultInfo());
    }
  }

  public void activateUser(String userEmail) throws EmailAlreadyExistsFault_Exception, InvalidEmailFault_Exception {
    Value maxValue = getMaxValue(userEmail);

    if (maxValue.getTag().getSeq() > 0)
      throw new EmailAlreadyExistsFault_Exception("Email already in use.");

    try {
      //register user on replicas
      setMaxValue(userEmail, newValue(maxValue.getVal(), maxValue.getTag()));

      //register user on cache
      addUserToCache(userEmail, maxValue);

    } catch (InvalidPointsFault_Exception e) {
      throw new RuntimeException(e.getMessage());
    }
  }

  public int pointsBalance(String userEmail) throws InvalidEmailFault_Exception {
    Value value = getUserValueFromCache(userEmail);
    return value.getVal();
  }

  public int addPoints(String userEmail, int pointsToAdd)
      throws InvalidEmailFault_Exception, InvalidPointsFault_Exception {
      int points = 0;

      if (pointsToAdd <= 0)
        throwInvalidPointsFault("Points cannot be negative!");

      Value cacheValue = getUserValueFromCache(userEmail);

      points = cacheValue.getVal() + pointsToAdd;
      Value updateCacheValue = newValue(points, cacheValue.getTag());

      //store information on replicas
      setMaxValue(userEmail, updateCacheValue);

      //keep cache consistent
      updateUserCacheValue(userEmail, updateCacheValue);

      return points;
  }

  public int spendPoints(String userEmail, int pointsToSpend)
      throws InvalidEmailFault_Exception, InvalidPointsFault_Exception, NotEnoughBalanceFault_Exception {
      int points = 0;

      if (pointsToSpend <= 0)
        throwInvalidPointsFault("Points cannot be negative!");

      Value cacheValue = getUserValueFromCache(userEmail);

      points = cacheValue.getVal() - pointsToSpend;
      if (points < 0) {
        throw new NotEnoughBalanceFault_Exception("Not Enough Points to spend");
      }
      Value updateCacheValue = newValue(points, cacheValue.getTag());

      //store information on replicas
      setMaxValue(userEmail, updateCacheValue);

      //keep cache consistent
      updateUserCacheValue(userEmail, updateCacheValue);

      return points;
  }

  private Value newValue(int val, Tag t) {
    Tag tag = new Tag();
    tag.setSeq(t.getSeq() + 1);
    tag.setCid(t.getCid());

    Value value = new Value();
    value.setVal(val);
    value.setTag(tag);
    return value;
  }

  private void addUserToCache(final String userEmail, final Value value) {
    if (!cache.containsKey(userEmail))
      cache.put(userEmail, value);
  }

  private Value getUserValueFromCache(final String userEmail) throws InvalidEmailFault_Exception {
    Value value = cache.get(userEmail);
    if (value == null) throw new InvalidEmailFault_Exception("Account/User does not exists", null);
    return value;
  }

  private void updateUserCacheValue(final String userEmail, Value value) {
    cache.replace(userEmail, value);
  }
  // remote invocation methods ----------------------------------------------

  public Value read(String userEmail) throws InvalidEmailFault_Exception {
    return port.read(userEmail);
  }

  public Response<ReadResponse> readAsync(String userEmail) {
    return port.readAsync(userEmail);
  }

  public String write(String userEmail, int points, Tag t) throws InvalidEmailFault_Exception, InvalidPointsFault_Exception {
    return port.write(userEmail, points, t);
  }

  public Response<WriteResponse> writeAsync(String userEmail, int points, Tag t) {
    return port.writeAsync(userEmail, points, t);
  }

  // control operations -----------------------------------------------------
  public String ctrlPing(String inputMessage) {
    StringBuilder builder = new StringBuilder();

    for(PointsPortType p : ports) {
      String aux = p.ctrlPing(inputMessage);
      if ( p.ctrlPing(inputMessage) != null )
        builder.append( p.ctrlPing(inputMessage) );
    }
    return builder.toString();
  }

  public void ctrlClear() {
    for(PointsPortType p : ports) {
      p.ctrlClear();
    }
  }

  public void ctrlInit(int startPoints) throws BadInitFault_Exception {
    for (PointsPortType p : ports) {
      p.ctrlInit(startPoints);
    }
  }

  /** Helper to throw a new InvalidPointsFault exception. */
  private void throwInvalidPointsFault(final String message) throws InvalidPointsFault_Exception {
    InvalidPointsFault faultInfo = new InvalidPointsFault();
    faultInfo.setMessage(message);
    throw new InvalidPointsFault_Exception(message, faultInfo);
  }

}
