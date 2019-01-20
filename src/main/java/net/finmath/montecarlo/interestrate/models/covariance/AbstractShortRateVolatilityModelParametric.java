/*
 * (c) Copyright Christian P. Fries, Germany. Contact: email@christian-fries.de.
 *
 * Created on 20.05.2006
 */
package net.finmath.montecarlo.interestrate.models.covariance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.finmath.exception.CalculationException;
import net.finmath.montecarlo.BrownianMotion;
import net.finmath.montecarlo.BrownianMotionLazyInit;
import net.finmath.montecarlo.interestrate.CalibrationProduct;
import net.finmath.montecarlo.interestrate.LIBORMonteCarloSimulationFromLIBORModel;
import net.finmath.montecarlo.interestrate.ShortRateModel;
import net.finmath.montecarlo.interestrate.models.HullWhiteModel;
import net.finmath.montecarlo.process.EulerSchemeFromProcessModel;
import net.finmath.optimizer.Optimizer;
import net.finmath.optimizer.Optimizer.ObjectiveFunction;
import net.finmath.optimizer.OptimizerFactory;
import net.finmath.optimizer.OptimizerFactoryLevenbergMarquardt;
import net.finmath.optimizer.SolverException;
import net.finmath.stochastic.RandomVariable;
import net.finmath.time.TimeDiscretization;

/**
 * Base class for parametric volatility models, see also {@link AbstractShortRateVolatilityModel}.
 *
 * Parametric models feature a parameter vector which can be inspected
 * and modified for calibration purposes.
 *
 * The parameter vector may have zero length, which indicated that the model
 * is not calibrateable.
 *
 * This class includes the implementation of a generic calibration algorithm.
 * If you provide an arbitrary list of calibration products, the class can return
 * a new instance where the parameters are chosen such that the (weighted) root-mean-square
 * error of the difference of the value of the calibration products and given target
 * values is minimized.
 *
 * @author Ruben Duarte
 * @version 1.1
 */
public abstract class AbstractShortRateVolatilityModelParametric extends AbstractShortRateVolatilityModel implements ShortRateVolatilityModelCalibrateable {

	private static final long serialVersionUID = 7015719361182945464L;

	private static final Logger logger = Logger.getLogger("net.finmath");

	/**
	 * Constructor consuming time discretization.
	 *
	 * @param timeDiscretization The vector of simulation time discretization points.
	 */
	public AbstractShortRateVolatilityModelParametric(TimeDiscretization timeDiscretization) {
		super(timeDiscretization);
	}

	/**
	 * Get the parameters of determining this parametric
	 * volatility model. The parameters are usually free parameters
	 * which may be used in calibration.
	 *
	 * @return Parameter vector.
	 */
	public abstract double[]	getParameter();


	@Override
	public abstract Object clone();

	/**
	 * Return an instance of this model using a new set of parameters.
	 * Note: To improve performance it is admissible to return the same instance of the object given that the parameters have not changed. Models should be immutable.
	 *
	 * @param parameters The new set of parameters.
	 * @return An instance of AbstractShortRateVolatilityModel with modified parameters.
	 */
	public abstract AbstractShortRateVolatilityModelParametric getCloneWithModifiedParameters(double[] parameters);

	/**
	 * Performs a generic calibration of the parametric model by
	 * trying to match a given vector of calibration product to a given vector of target values
	 * using a given vector of weights.
	 *
	 * Optional calibration parameters may be passed using the map calibrationParameters. The keys are (<code>String</code>s):
	 * <ul>
	 * 	<li><tt>brownianMotion</tt>: Under this key an object implementing {@link net.finmath.montecarlo.BrownianMotion} may be provided. If so, this Brownian motion is used to build the valuation model.</li>
	 * 	<li><tt>maxIterations</tt>: Under this key an object of type Integer may be provided specifying the maximum number of iterations.</li>
	 * 	<li><tt>accuracy</tt>: Under this key an object of type Double may be provided specifying the desired accuracy. Note that this is understood in the sense that the solver will stop if the iteration does not improve by more than this number.</li>
	 * </ul>
	 *
	 * @param calibrationModel The LIBOR market model to be used for calibrations (specifies forward curve and tenor discretization).
	 * @param calibrationProducts The array of calibration products.
	 * @param calibrationParameters A map of type Map&lt;String, Object&gt; specifying some (optional) calibration parameters.
	 * @return A new parametric model of the same type than <code>this</code> one, but with calibrated parameters.
	 * @throws CalculationException Thrown if calibration has failed.
	 */
	public AbstractShortRateVolatilityModelParametric getCloneCalibrated(final ShortRateModel calibrationModel, final CalibrationProduct[] calibrationProducts, Map<String,Object> calibrationParameters) throws CalculationException {

		if(calibrationParameters == null) {
			calibrationParameters = new HashMap<>();
		}
		Integer numberOfPathsParameter	= (Integer)calibrationParameters.get("numberOfPaths");
		Integer seedParameter			= (Integer)calibrationParameters.get("seed");
		Integer maxIterationsParameter	= (Integer)calibrationParameters.get("maxIterations");
		Double	parameterStepParameter	= (Double)calibrationParameters.get("parameterStep");
		Double	accuracyParameter		= (Double)calibrationParameters.get("accuracy");
		BrownianMotion brownianMotionParameter	= (BrownianMotion)calibrationParameters.get("brownianMotion");

		double[] initialParameters = this.getParameter();
		double[] lowerBound = new double[initialParameters.length];
		double[] upperBound = new double[initialParameters.length];
		double[] parameterStep = new double[initialParameters.length];
		double[] zero = new double[calibrationProducts.length];
		Arrays.fill(lowerBound, Double.NEGATIVE_INFINITY);
		Arrays.fill(upperBound, Double.POSITIVE_INFINITY);
		Arrays.fill(parameterStep, parameterStepParameter != null ? parameterStepParameter.doubleValue() : 1E-4);
		Arrays.fill(zero, 0);

		/*
		 * We allow for 2 simultaneous calibration models.
		 * Note: In the case of a Monte-Carlo calibration, the memory requirement is that of
		 * one model with 2 times the number of paths. In the case of an analytic calibration
		 * memory requirement is not the limiting factor.
		 */
		int numberOfThreads = 2;
		OptimizerFactory optimizerFactoryParameter = (OptimizerFactory)calibrationParameters.get("optimizerFactory");

		int numberOfPaths	= numberOfPathsParameter != null ? numberOfPathsParameter.intValue() : 2000;
		int seed			= seedParameter != null ? seedParameter.intValue() : 31415;
		int numberOfFactors	= 2;	// Hull-White model implementation uses two factors for exact discretization.
		int maxIterations	= maxIterationsParameter != null ? maxIterationsParameter.intValue() : 400;
		double accuracy		= accuracyParameter != null ? accuracyParameter.doubleValue() : 1E-7;
		final BrownianMotion brownianMotion = brownianMotionParameter != null ? brownianMotionParameter : new BrownianMotionLazyInit(getTimeDiscretization(), numberOfFactors, numberOfPaths, seed);
		OptimizerFactory optimizerFactory = optimizerFactoryParameter != null ? optimizerFactoryParameter : new OptimizerFactoryLevenbergMarquardt(maxIterations, accuracy, numberOfThreads);

		final ExecutorService executor = null;
		//		int numberOfThreadsForProductValuation = 2 * Math.max(2, Runtime.getRuntime().availableProcessors());
		//		Executors.newFixedThreadPool(numberOfThreadsForProductValuation);

		ObjectiveFunction calibrationError = new ObjectiveFunction() {
			// Calculate model values for given parameters
			@Override
			public void setValues(double[] parameters, double[] values) throws SolverException {

				AbstractShortRateVolatilityModelParametric calibrationVolatilityModel = AbstractShortRateVolatilityModelParametric.this.getCloneWithModifiedParameters(parameters);

				// Create a HullWhiteModel with the new volatility structure.
				// TODO the case has be removed after the interface has been refactored:
				HullWhiteModel model = (HullWhiteModel)calibrationModel.getCloneWithModifiedVolatilityModel(calibrationVolatilityModel);
				EulerSchemeFromProcessModel process = new EulerSchemeFromProcessModel(brownianMotion);
				final LIBORMonteCarloSimulationFromLIBORModel modelMonteCarloSimulation = new LIBORMonteCarloSimulationFromLIBORModel(model, process);

				ArrayList<Future<RandomVariable>> valueFutures = new ArrayList<>(calibrationProducts.length);
				for(int calibrationProductIndex=0; calibrationProductIndex<calibrationProducts.length; calibrationProductIndex++) {
					final int workerCalibrationProductIndex = calibrationProductIndex;
					Callable<RandomVariable> worker = new  Callable<RandomVariable>() {
						@Override
						public RandomVariable call() {
							try {
								return calibrationProducts[workerCalibrationProductIndex].getProduct().getValue(0.0, modelMonteCarloSimulation).sub(calibrationProducts[workerCalibrationProductIndex].getTargetValue()).mult(calibrationProducts[workerCalibrationProductIndex].getWeight());
							} catch (CalculationException e) {
								// We do not signal exceptions to keep the solver working and automatically exclude non-working calibration products.
								return null;
							} catch (Exception e) {
								// We do not signal exceptions to keep the solver working and automatically exclude non-working calibration products.
								return null;
							}
						}
					};
					if(executor != null) {
						Future<RandomVariable> valueFuture = executor.submit(worker);
						valueFutures.add(calibrationProductIndex, valueFuture);
					}
					else {
						FutureTask<RandomVariable> valueFutureTask = new FutureTask<>(worker);
						valueFutureTask.run();
						valueFutures.add(calibrationProductIndex, valueFutureTask);
					}
				}
				for(int calibrationProductIndex=0; calibrationProductIndex<calibrationProducts.length; calibrationProductIndex++) {
					try {
						RandomVariable value = valueFutures.get(calibrationProductIndex).get();
						values[calibrationProductIndex] = value != null ? value.getAverage() : 0.0;;
					}
					catch (InterruptedException e) {
						throw new SolverException(e);
					} catch (ExecutionException e) {
						throw new SolverException(e);
					}
				}
			}
		};

		Optimizer optimizer = optimizerFactory.getOptimizer(calibrationError, initialParameters, lowerBound, upperBound, parameterStep, zero);
		try {
			optimizer.run();
		}
		catch(SolverException e) {
			throw new CalculationException(e);
		}
		finally {
			if(executor != null) {
				executor.shutdown();
			}
		}

		// Get volatility model corresponding to the best parameter set.
		double[] bestParameters = optimizer.getBestFitParameters();
		AbstractShortRateVolatilityModelParametric calibrationVolatilityModel = this.getCloneWithModifiedParameters(bestParameters);

		// Diagnostic output
		if (logger.isLoggable(Level.FINE)) {
			logger.fine("The solver required " + optimizer.getIterations() + " iterations. The best fit parameters are:");

			String logString = "Best parameters:";
			for(int i=0; i<bestParameters.length; i++) {
				logString += "\tparameter["+i+"]: " + bestParameters[i];
			}
			logger.fine(logString);
		}

		return calibrationVolatilityModel;
	}

	@Override
	public String toString() {
		return "AbstractShortRateVolatilityModelParametric [getParameter()="
				+ Arrays.toString(getParameter()) + "]";
	}
}
