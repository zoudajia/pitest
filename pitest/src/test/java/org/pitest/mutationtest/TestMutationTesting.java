/*
 * Copyright 2010 Henry Coles
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.mutationtest;

import static org.junit.Assert.assertEquals;
import static org.pitest.mutationtest.results.DetectionStatus.KILLED;
import static org.pitest.mutationtest.results.DetectionStatus.MEMORY_ERROR;
import static org.pitest.mutationtest.results.DetectionStatus.NON_VIABLE;
import static org.pitest.mutationtest.results.DetectionStatus.NO_COVERAGE;
import static org.pitest.mutationtest.results.DetectionStatus.SURVIVED;
import static org.pitest.mutationtest.results.DetectionStatus.TIMED_OUT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.MockitoAnnotations;
import org.pitest.DefaultStaticConfig;
import org.pitest.Pitest;
import org.pitest.annotations.ClassUnderTest;
import org.pitest.containers.UnContainer;
import org.pitest.extension.Configuration;
import org.pitest.extension.Container;
import org.pitest.extension.TestUnit;
import org.pitest.functional.Prelude;
import org.pitest.functional.predicate.False;
import org.pitest.functional.predicate.Predicate;
import org.pitest.internal.ClassPath;
import org.pitest.internal.ClassloaderByteArraySource;
import org.pitest.internal.IsolationUtils;
import org.pitest.mutationtest.engine.MutationEngine;
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory;
import org.pitest.mutationtest.filter.UnfilteredMutationFilter;
import org.pitest.mutationtest.instrument.JarCreatingJarFinder;
import org.pitest.mutationtest.instrument.PercentAndConstantTimeoutStrategy;
import org.pitest.mutationtest.results.DetectionStatus;
import org.pitest.testutil.ConfigurationForTesting;
import org.pitest.testutil.IgnoreAnnotationForTesting;
import org.pitest.testutil.TestAnnotationForTesting;
import org.pitest.util.JavaAgent;

public class TestMutationTesting {

  private Pitest              pit;
  private Container           container;
  private DefaultStaticConfig staticConfig;
  private Configuration       config;

  private MetaDataExtractor   metaDataExtractor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    this.config = new ConfigurationForTesting();
    this.metaDataExtractor = new MetaDataExtractor();
    this.container = new UnContainer();
    this.staticConfig = new DefaultStaticConfig();
    this.staticConfig.addTestListener(this.metaDataExtractor);
    this.pit = new Pitest(this.staticConfig);
  }

  public static class NoMutations {

  }

  public static class OneMutation {
    public static int returnOne() {
      return 1;
    }
  }

  public static class ThreeMutations {
    public static int returnOne() {
      return 1;
    }

    public static int returnTwo() {
      return 2;
    }

    public static int returnThree() {
      return 3;
    }
  }

  @ClassUnderTest(OneMutation.class)
  public static class OneMutationFullTest {
    @TestAnnotationForTesting
    public void testReturnOne() {
      assertEquals(1, OneMutation.returnOne());
    }
  }

  @Test
  public void shouldKillAllCoveredMutations() {
    run(OneMutation.class, OneMutationFullTest.class,
        Mutator.RETURN_VALS.asCollection());
    verifyResults(KILLED);
  }

  public static class ThreeMutationsTwoMeaningfullTests {
    @TestAnnotationForTesting
    public void testReturnOne() {
      assertEquals(1, ThreeMutations.returnOne());
    }

    @TestAnnotationForTesting
    public void testReturnTwo() {
      assertEquals(2, ThreeMutations.returnTwo());
    }

    @TestAnnotationForTesting
    public void coverButDoNotTestReturnThree() {
      ThreeMutations.returnThree();
    }
  }

  @Test
  public void shouldDetectedMixOfSurvivingAndKilledMutations() {
    run(ThreeMutations.class, ThreeMutationsTwoMeaningfullTests.class,
        Mutator.RETURN_VALS.asCollection());
    verifyResults(SURVIVED, KILLED, KILLED);
  }

  public static class FailingTest {
    @TestAnnotationForTesting
    public void fail() {
      assertEquals(1, 2);
    }
  }

  public static class NoMutationsTest {
    @TestAnnotationForTesting
    public void pass() {

    }
  }

  @Test
  public void shouldReportNoResultsIfNoMutationsPossible() {
    run(NoMutations.class, NoMutationsTest.class,
        Mutator.RETURN_VALS.asCollection());
    verifyResults();
  }

  public static class NoTests {
    @TestAnnotationForTesting
    @IgnoreAnnotationForTesting
    public void fail() {
      System.out.println("oops");
      assertEquals(1, 2);
    }
  }

  @Test
  public void shouldReportStatusOfNoCoverageWhenNoTestsAvailable() {
    run(ThreeMutations.class, NoTests.class, Mutator.RETURN_VALS.asCollection());
    verifyResults(NO_COVERAGE, NO_COVERAGE, NO_COVERAGE);
  }

  public static class OneMutationTest {

  }

  public static class InfiniteLoop {
    public static int loop() {
      int i = 1;
      do {
        i++;
        try {
          Thread.sleep(1);
        } catch (final InterruptedException e) {
          e.printStackTrace();
        }
      } while (i < 1);
      i++;
      return i;
    }
  }

  public static class InfiniteLoopTest {
    @TestAnnotationForTesting()
    public void pass() {
      assertEquals(3, InfiniteLoop.loop());
    }
  }

  @Test(timeout = 30000)
  public void shouldDetectAndEscapeFromInfiniteLoopsCausedByMutations() {
    run(InfiniteLoop.class, InfiniteLoopTest.class,
        Mutator.INCREMENTS.asCollection());
    verifyResults(KILLED, TIMED_OUT);
  }

  public static class OneMutationFullTestWithSystemPropertyDependency {
    @TestAnnotationForTesting
    public void testReturnOne() {
      if (System.getProperty("foo").equals("foo")) {
        assertEquals(1, OneMutation.returnOne());
      }
    }
  }

  @Test
  public void shouldExportSystemPropertiesToSlaveProcess() {
    // System.setProperty("foo", "foo");
    // note surefire is configured to launch this test with -Dfoo=foo
    run(OneMutation.class,
        OneMutationFullTestWithSystemPropertyDependency.class,
        Mutator.RETURN_VALS.asCollection());
    verifyResults(KILLED);
  }

  public static class UnviableMutationsTest {
    @TestAnnotationForTesting
    public void test() {
      new OneMutation();
      OneMutation.returnOne();
    }
  }

  @Test
  public void shouldDetectUnviableMutations() {
    run(OneMutation.class, UnviableMutationsTest.class,
        Collections.singleton(new UnviableClassMutator()));
    verifyResults(NON_VIABLE, NON_VIABLE);

  }

  public static class EatsMemoryWhenMutated {
    public static int loop() throws InterruptedException {
      int i = 1;
      final List<String[]> vals = new ArrayList<String[]>();
      Thread.sleep(1500);
      do {
        i++;
        vals.add(new String[9999999]);
        vals.add(new String[9999999]);
        vals.add(new String[9999999]);
        vals.add(new String[9999999]);
      } while (i < 1);
      i++;
      return i;
    }
  }

  public static class EatsMemoryTest {
    @TestAnnotationForTesting()
    public void pass() throws InterruptedException {
      assertEquals(3, EatsMemoryWhenMutated.loop());
    }
  }

  @Ignore("flakey")
  @Test(timeout = 30000)
  public void shouldRecoverFromOutOfMemoryError() {
    run(EatsMemoryWhenMutated.class, EatsMemoryTest.class,
        Mutator.INCREMENTS.asCollection());
    verifyResults(KILLED, MEMORY_ERROR);
  }

  private void run(final Class<?> clazz, final Class<?> test,
      final Collection<? extends MethodMutatorFactory> mutators) {

    final ReportOptions data = new ReportOptions();

    final Set<Predicate<String>> tests = Collections.singleton(Prelude
        .isEqualTo(test.getName()));
    data.setTargetTests(tests);
    data.setDependencyAnalysisMaxDistance(-1);

    final Set<Predicate<String>> mutees = Collections.singleton(Prelude
        .isEqualTo(clazz.getName()));
    data.setTargetClasses(mutees);

    data.setTimeoutConstant(PercentAndConstantTimeoutStrategy.DEFAULT_CONSTANT);
    data.setTimeoutFactor(PercentAndConstantTimeoutStrategy.DEFAULT_FACTOR);

    final ArrayList<Predicate<String>> inScope = new ArrayList<Predicate<String>>();
    inScope.addAll(mutees);
    inScope.addAll(tests);

    data.setClassesInScope(inScope);

    final JavaAgent agent = new JarCreatingJarFinder();

    try {
      createEngineAndRun(data, agent, mutators);
    } finally {
      agent.close();
    }
  }

  private void createEngineAndRun(final ReportOptions data,
      final JavaAgent agent,
      final Collection<? extends MethodMutatorFactory> mutators) {
    final CoverageDatabase coverageDatabase = new DefaultCoverageDatabase(
        this.config, new ClassPath(), agent, data);

    coverageDatabase.initialise();

    final Collection<ClassGrouping> codeClasses = coverageDatabase
        .getGroupedClasses();

    final MutationEngine engine = DefaultMutationConfigFactory.createEngine(
        false, False.<String> instance(), Collections.<String> emptyList(),
        mutators);

    final MutationConfig mutationConfig = new MutationConfig(engine,
        Collections.<String> emptyList());
    final MutationTestBuilder builder = new MutationTestBuilder(mutationConfig,
        UnfilteredMutationFilter.factory(), this.config, data, agent,
        new ClassloaderByteArraySource(IsolationUtils.getContextClassLoader()));

    final List<TestUnit> tus = builder.createMutationTestUnits(codeClasses,
        this.config, coverageDatabase);

    this.pit.run(this.container, tus);
  }

  protected void verifyResults(final DetectionStatus... detectionStatus) {
    final List<DetectionStatus> expected = Arrays.asList(detectionStatus);
    final List<DetectionStatus> actual = this.metaDataExtractor
        .getDetectionStatus();

    Collections.sort(expected);
    Collections.sort(actual);

    assertEquals(expected, actual);
  }

}
