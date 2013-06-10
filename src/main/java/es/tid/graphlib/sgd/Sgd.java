package es.tid.graphlib.sgd;

import java.util.Map.Entry;

import org.apache.giraph.Algorithm;
import org.apache.giraph.aggregators.DoubleSumAggregator;
import org.apache.giraph.edge.DefaultEdge;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;

import es.tid.graphlib.utils.DoubleArrayListHashMapWritable;
import es.tid.graphlib.utils.DoubleArrayListWritable;
import es.tid.graphlib.utils.MessageWrapper;

/**
 * Demonstrates the Pregel Stochastic Gradient Descent (SGD) implementation.
 */
@Algorithm(
  name = "Stochastic Gradient Descent (SGD)",
  description = "Minimizes the error in users preferences predictions")

public class Sgd extends Vertex<IntWritable, DoubleArrayListHashMapWritable,
  DoubleWritable, MessageWrapper> {
  /** Keyword for parameter enabling delta caching */
  public static final String DELTA_CACHING = "sgd.delta.caching";
  /** Default value for parameter enabling delta caching */
  public static final boolean DELTA_CACHING_DEFAULT = false;
  /** Keyword for parameter enabling the RMSE aggregator */
  public static final String RMSE_AGGREGATOR = "sgd.rmse.aggregator";
  /** Default value for parameter enabling the RMSE aggregator */
  public static final boolean RMSE_AGGREGATOR_DEFAULT = false;
  /** Keyword for parameter choosing the halt factor */
  public static final String HALT_FACTOR = "sgd.halt.factor";
  /** Default value for parameter choosing the halt factor */
  public static final String HALT_FACTOR_DEFAULT = "basic";
  /** Keyword for parameter setting the number of iterations */
  public static final String ITERATIONS_KEYWORD = "sgd.iterations";
  /** Default value for ITERATIONS */
  public static final int ITERATIONS_DEFAULT = 10;
  /** Keyword for parameter setting the convergence tolerance parameter
   *  depending on the version enabled; l2norm or rmse */
  public static final String TOLERANCE_KEYWORD = "sgd.tolerance";
  /** Default value for TOLERANCE */
  public static final float TOLERANCE_DEFAULT = 1;
  /** Keyword for parameter setting the Regularization parameter LAMBDA */
  public static final String LAMBDA_KEYWORD = "sgd.lambda";
  /** Default value for LABDA */
  public static final float LAMBDA_DEFAULT = 0.01f;
  /** Keyword for parameter setting the learning rate GAMMA */
  public static final String GAMMA_KEYWORD = "sgd.gamma";
  /** Default value for GAMMA */
  public static final float GAMMA_DEFAULT = 0.005f;
  /** Keyword for parameter setting the Latent Vector Size */
  public static final String VECTOR_SIZE_KEYWORD = "sgd.vector.size";
  /** Default value for GAMMA */
  public static final int VECTOR_SIZE_DEFAULT = 2;
  /** Max rating */
  public static final double MAX = 5;
  /** Min rating */
  public static final double MIN = 0;
  /** Decimals */
  public static final int DECIMALS = 4;
  /** Factor Error: it may be RMSD or L2NORM on initial&final vector */
  private double haltFactor = 0d;
  /** Number of updates - used in the Output Format */
  private int updatesNum = 0;
  /** Observed Value - Rating */
  private double observed = 0d;
  /** Error */
  private double err = 0d;
  /** RMSE Error */
  private double rmseErr = 0d;
  /** Initial vector value to be used for the L2Norm case */
  private DoubleArrayListWritable initialValue =
    new DoubleArrayListWritable();
  /** Type of vertex 0 for user, 1 for item - used in the Output Format*/
  private boolean item = false;

  /*
   * Compute method
   * @param messages Messages received
   */
  public void compute(Iterable<MessageWrapper> messages) {
    /*
     * Counter of messages received
     * This is different from getNumEdges() because a
     * neighbor may not send a message
     */
    int msgCounter = 0;
    /* Flag for checking if parameter for RMSE aggregator received */
    boolean rmseFlag = getContext().getConfiguration().getBoolean(
      RMSE_AGGREGATOR, RMSE_AGGREGATOR_DEFAULT);
    /*
     * Flag for checking which termination factor to use:
     * basic, rmse, l2norm
     **/
    String factorFlag = getContext().getConfiguration().get(HALT_FACTOR,
      HALT_FACTOR_DEFAULT);
    /* Flag for checking if delta caching is enabled */
    boolean deltaFlag = getContext().getConfiguration().getBoolean(
      DELTA_CACHING, DELTA_CACHING_DEFAULT);
    /* Set the number of iterations */
    int iterations = getContext().getConfiguration().getInt(ITERATIONS_KEYWORD,
      ITERATIONS_DEFAULT);
    /* Set the Convergence Tolerance */
    float tolerance = getContext().getConfiguration()
      .getFloat(TOLERANCE_KEYWORD, TOLERANCE_DEFAULT);
    /* Set the Regularization Parameter LAMBDA */
    float lambda = getContext().getConfiguration()
      .getFloat(LAMBDA_KEYWORD, LAMBDA_DEFAULT);
    /* Set the Learning Rate GAMMA */
    float gamma = getContext().getConfiguration()
      .getFloat(GAMMA_KEYWORD, GAMMA_DEFAULT);
    /* Set the size of the Latent Vector*/
    int vectorSize = getContext().getConfiguration()
      .getInt(VECTOR_SIZE_KEYWORD, VECTOR_SIZE_DEFAULT);
    
    /* First superstep for users (superstep 0) & items (superstep 1) */
    if (getSuperstep() < 2) {
      initLatentVector(vectorSize);
    }
    /* Set flag for items - used in the Output Format */
    if (getSuperstep() == 1) {
      item = true;
    }

    /*
     * System.out.println("****  Vertex: "+getId()+", superstep:"+getSuperstep
     * ()+", item:" + item + ", " + getValue().getLatentVector());
     */
    /* Used if RMSE version or RMSE aggregator is enabled */
    rmseErr = 0d;
    /* Used if delta caching is enabled*/
    boolean neighUpdated = false;

    /* FOR LOOP - for each message */
    for (MessageWrapper message : messages) {
      msgCounter++;
      /*
       * System.out.println(" [RECEIVE] from " + message.getSourceId().get() +
       * ", " + message.getMessage());
       */
      /*
       * First superstep for items:
       * 1. Create outgoing edges of items
       * 2. Store the rating given from users in the outgoing edges
       */

      if (getSuperstep() == 1) {
        observed = message.getMessage().get(message.getMessage().size() - 1)
          .get();
        DefaultEdge<IntWritable, DoubleWritable> edge =
          new DefaultEdge<IntWritable, DoubleWritable>();
        edge.setTargetVertexId(message.getSourceId());
        edge.setValue(new DoubleWritable(observed));
        // System.out.println("   Adding edge:" + edge);
        addEdge(edge);
        /* Remove the last value from message
         * It's there only for the 1st round of items
         */
        message.getMessage().remove(message.getMessage().size() - 1);
      }
      /* If delta caching is enabled
       * For the first superstep of either users or items, save their values
       * For the rest supersteps, updated their values
       * Do NOT run SGD computation in this for loop
       */
      if (deltaFlag) {
        /* Create table with neighbors latent values and IDs */
        if (getSuperstep() == 1 || getSuperstep() == 2) {
          getValue().setNeighborValue(message.getSourceId(),
            message.getMessage());
        }
        /* For the next rounds, update their values if necessary */
        if (getSuperstep() > 2) {
          if (updateNeighValues(getValue()
            .getNeighValue(message.getSourceId()), message.getMessage(),
            vectorSize)) {
            neighUpdated = true;
          }
        }
      }
      /* If delta caching is NOT enabled */
      if (!deltaFlag) {
        /* Calculate error */
        observed = (double) getEdgeValue(message.getSourceId()).get();
        err = getError(vectorSize, getValue().getLatentVector(),
          message.getMessage(),
          observed);
        /* Change the Vertex Latent Vector based on SGD equation */
        runSgdAlgorithm(vectorSize, message.getMessage(), lambda, gamma);
        err = getError(vectorSize, getValue().getLatentVector(),
          message.getMessage(),
          observed);
        /* If termination flag is set to RMSE or RMSE aggregator is enabled */
        if (factorFlag.equals("rmse") || rmseFlag) {
          rmseErr += Math.pow(err, 2);
        }
      }
    } /* END OF LOOP - for each message */

    /* If delta caching is enabled
     * Go through the edges and execute the SGD computation
     */
    if (deltaFlag && getSuperstep() > 0) {
      /* FOR LOOP - for each edge */
      for (Entry<IntWritable, DoubleArrayListWritable> vvertex : getValue()
        .getAllNeighValue().entrySet()) {
        /* Calculate error */
        observed = (double) getEdgeValue(vvertex.getKey()).get();
        err = getError(vectorSize, getValue().getLatentVector(),
          vvertex.getValue(),
          observed);
        /*
         * If at least one neighbor has changed its latent vector,
         * then calculation of vertex can not be avoided
         */
        if (neighUpdated) {
          /* Change the Vertex Latent Vector based on SGD equation */
          runSgdAlgorithm(vectorSize, vvertex.getValue(), lambda, gamma);
          err = getError(vectorSize, getValue().getLatentVector(),
            vvertex.getValue(),
            observed);
          /* If termination flag is set to RMSE or RMSE aggregator is true */
          if (factorFlag.equals("rmse") || rmseFlag) {
            rmseErr += Math.pow(err, 2);
          }
        } /* Eof if (neighUpdated) */
      }  /* END OF LOOP - for each edge */
    } /* Eof if (deltaFlag > 0f && getSuperstep() > 0) */

    // If halt factor is set to basic - set number of iterations + 1
    if (factorFlag.equals("basic")) {
      haltFactor = tolerance + 1;
    }
    /* If RMSE aggregator flag is true - send rmseErr to aggregator */
    if (rmseFlag) {
      this.aggregate(RMSE_AGGREGATOR, new DoubleWritable(rmseErr));
    }
    /* If termination factor is set to RMSE - set the RMSE parameter */
    if (factorFlag.equals("rmse")) {
      haltFactor = getRMSE(msgCounter);
      //System.out.println("myRMSD: " + haltFactor);
    }
    /* If termination factor is set to L2NOrm - set the L2NORM parameter */
    if (factorFlag.equals("l2norm")) {
      haltFactor = getL2Norm(initialValue, getValue().getLatentVector());
      // System.out.println("NormVector: sqrt((initial[0]-final[0])^2 " +
      //"+ (initial[1]-final[1])^2): "
      // + err_factor);
    }
    if (getSuperstep() == 0 ||
      (haltFactor > tolerance && getSuperstep() < iterations)) {
      sendMsgs();
    }
    // haltFactor is used in the OutputFormat file. --> To print the error
    if (factorFlag.equals("basic")) {
      haltFactor = err;
    }
    voteToHalt();
  } // EofCompute

  /**
   * Return type of current vertex
   *
   * @return item
   */
  public boolean isItem() {
    return item;
  }

  /*** Initialize Vertex Latent Vector */
  public void initLatentVector(int vectorSize) {
    DoubleArrayListHashMapWritable value =
      new DoubleArrayListHashMapWritable();
    for (int i = 0; i < vectorSize; i++) {
      value.setLatentVector(i, new DoubleWritable(
        ((double) (getId().get() + i) % 100d) / 100d));
    }
    setValue(value);
    // System.out.println("[INIT] value: " + value.getLatentVector());
    /* For L2Norm */
    initialValue = getValue().getLatentVector();
  }

  /**
   * Modify Vertex Latent Vector based on SGD equation
   *
   * @param vvertex Vertex value
   */
  public void runSgdAlgorithm(int vectorSize,
    DoubleArrayListWritable vvertex, float lambda, float gamma) {
    /**
     * vertex_vector = vertex_vector + part3
     *
     * part1 = LAMBDA * vertex_vector
     * part2 = real_value - dot_product(vertex_vector,other_vertex_vector)) *
     * other_vertex_vector
     * part3 = - GAMMA * (part1 + part2)
     */
    DoubleArrayListWritable part1 = new DoubleArrayListWritable();
    DoubleArrayListWritable part2 = new DoubleArrayListWritable();
    DoubleArrayListWritable part3 = new DoubleArrayListWritable();
    DoubleArrayListWritable value = new DoubleArrayListWritable();
    part1 = numMatrixProduct(vectorSize, (double) lambda,
      getValue().getLatentVector());
    part2 = numMatrixProduct(vectorSize, (double) err, vvertex);
    part3 = numMatrixProduct(vectorSize, (double) -gamma,
      dotAddition(vectorSize, part1, part2));
    value = dotAddition(vectorSize, getValue().getLatentVector(), part3);
    // System.out.print("Latent Vector: " + value);
    keepXdecimals(value, DECIMALS);
    // System.out.println(" , 4 decimals: " + value);
    getValue().setLatentVector(value);
    updatesNum++;
  }

  /**
   * Decimal Precision of latent vector values
   *
   * @param value Value to be truncated
   * @param x Number of decimals to keep
   */
  public void keepXdecimals(DoubleArrayListWritable value, int x) {
    double num = 1;
    for (int i = 0; i < x; i++) {
      num *= 10;
    }
    for (int i = 0; i < value.size(); i++) {
      value.set(i,
        new DoubleWritable(
          (double) (Math.round(value.get(i).get() * num) / num)));
    }
  }

  /**
   * Update Neighbor's values with the latest value
   *
   * @param curVal Current vertex value
   * @param latestVal Latest vertex value
   * @return updated True if vertex value is updated
   */
  public boolean updateNeighValues(DoubleArrayListWritable curVal,
    DoubleArrayListWritable latestVal, int vectorSize) {
    boolean updated = false;
    for (int i = 0; i < vectorSize; i++) {
      if (latestVal.get(i) != curVal.get(i)) {
        curVal.set(i, latestVal.get(i));
        updated = true;
        break;
      }
    }
    return updated;
  }

  /*** Send messages to neighbours */
  public void sendMsgs() {
    /* Create a message and wrap together the source id and the message */
    MessageWrapper message = new MessageWrapper();
    message.setSourceId(getId());

    if (getSuperstep() == 0) {
      for (Edge<IntWritable, DoubleWritable> edge : getEdges()) {
        DoubleArrayListWritable x = new DoubleArrayListWritable(getValue()
          .getLatentVector());
        x.add(new DoubleWritable(edge.getValue().get()));
        message.setMessage(x);
        sendMessage(edge.getTargetVertexId(), message);
      }
    } else {
      message.setMessage(getValue().getLatentVector());
      sendMessageToAllEdges(message);
    }
  }

  /**
   * Calculate the RMSE on the errors calculated by the current vertex
   *
   * @param msgCounter Count of messages received
   * @return RMSE result
   */
  public double getRMSE(int msgCounter) {
    return Math.sqrt(rmseErr / msgCounter);
  }

  /**
   * Calculate the L2Norm on the initial and final value of vertex
   *
   * @param valOld Old value
   * @param valNew New value
   * @return result of L2Norm equation
   * */
  public double getL2Norm(DoubleArrayListWritable valOld,
    DoubleArrayListWritable valNew) {
    double result = 0;
    for (int i = 0; i < valOld.size(); i++) {
      result += Math.pow(valOld.get(i).get() - valNew.get(i).get(), 2);
    }
    //System.out.println("L2norm: " + result);
    return Math.sqrt(result);
  }

  /**
   * Calculate the error: e=observed-predicted
   *
   * @param vectorA Vector A
   * @param vectorB Vector B
   * @param observed Observed value
   * @return Result from deducting observed value from predicted
   */
  public double getError(int vectorSize, DoubleArrayListWritable vectorA,
    DoubleArrayListWritable vectorB, double observed) {
    /*** Predicted value */
    //System.out.println("vectorA, vectorB");
    //vectorA.print();
    //vectorB.print();

    double predicted = dotProduct(vectorSize, vectorA, vectorB);
    predicted = Math.min(predicted, MAX);
    predicted = Math.max(predicted, MIN);
    return predicted - observed;
  }

  /**
   * Calculate the dot product of 2 vectors: vector1*vector2
   *
   * @param vectorA Vector A
   * @param vectorB Vector B
   * @return Result from dot product of 2 vectors
   */
  public double dotProduct(int vectorSize, DoubleArrayListWritable vectorA,
    DoubleArrayListWritable vectorB) {
    double result = 0d;
    for (int i = 0; i < vectorSize; i++) {
      result += vectorA.get(i).get() * vectorB.get(i).get();
    }
    return result;
  }

  /**
   * Calculate the dot addition of 2 vectors: vectorA+vectorB
   *
   * @param vectorA Vector A
   * @param vectorB Vector B
   * @return result Result from dot addition of the two vectors
   */
  public DoubleArrayListWritable dotAddition(int vectorSize,
    DoubleArrayListWritable vectorA,
    DoubleArrayListWritable vectorB) {
    DoubleArrayListWritable result = new DoubleArrayListWritable();
    for (int i = 0; i < vectorSize; i++) {
      result.add(new DoubleWritable
      (vectorA.get(i).get() + vectorB.get(i).get()));
    }
    return result;
  }

  /**
   * Calculate the product num*matirx
   *
   * @param num Number to be multiplied with matrix
   * @param matrix Matrix to be multiplied with number
   * @return result Result from multiplication
   */
  public DoubleArrayListWritable numMatrixProduct(int vectorSize,
    double num, DoubleArrayListWritable matrix) {
    DoubleArrayListWritable result = new DoubleArrayListWritable();
    for (int i = 0; i < vectorSize; i++) {
      result.add(new DoubleWritable(num * matrix.get(i).get()));
    }
    return result;
  }

  /**
   * Return amount of vertex updates
   *
   * @return nupdates
   * */
  public int getUpdates() {
    return updatesNum;
  }

  /**
   * Return amount of vertex updates
   *
   * @return haltFactor
   * */
  public double getHaltFactor() {
    return haltFactor;
  }

  /**
   * MasterCompute used with {@link SimpleMasterComputeVertex}.
   */
  public static class MasterCompute
    extends DefaultMasterCompute {
    @Override
    public void compute() {
      /* Set the Convergence Tolerance */
      float tolerance = getContext().getConfiguration()
        .getFloat(TOLERANCE_KEYWORD, TOLERANCE_DEFAULT);
      /* Flag for checking if parameter for RMSE aggregator received */
      boolean rmseFlag = getContext().getConfiguration().getBoolean(
        RMSE_AGGREGATOR, RMSE_AGGREGATOR_DEFAULT);
      double numRatings = 0;
      double totalRMSE = 0;
      if (getSuperstep() > 1) {
        // In superstep=1 only half edges are created (users to items)
        if (getSuperstep() == 2) {
          numRatings = getTotalNumEdges();
        } else {
          numRatings = getTotalNumEdges() / 2;
        }
        if (rmseFlag) {
          totalRMSE = Math.sqrt(((DoubleWritable)
            getAggregatedValue(RMSE_AGGREGATOR)).get() / numRatings);
        /* System.out.println("Superstep: " + getSuperstep() +
          ", [Aggregator] Added Values: " + getAggregatedValue(RMSE_AGG) +
            " / " + numRatings + " = " +
              ((DoubleWritable) getAggregatedValue(RMSE_AGG)).get() /
                numRatings + " --> sqrt(): " + totalRMSE); */
        System.out.println("SS:" + getSuperstep() + ", Total RMSE: "
          + totalRMSE);
        //getAggregatedValue(RMSE_AGG);
        }
        if (totalRMSE < tolerance) {
          //System.out.println("HALT!");
          haltComputation();
        }
      } // Eof if (superstep > 1)
    } // Eof Compute()

    @Override
    public void initialize() throws InstantiationException,
      IllegalAccessException {
      registerAggregator(RMSE_AGGREGATOR, DoubleSumAggregator.class);
    }
  }
}
