package net.tsyrklevich.censustaker.tests;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import net.tsyrklevich.censustaker.MainActivity;

public class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {

  public MainActivityTest() {
    super("net.tsyrklevich.censustaker", MainActivity.class);
  }

  public void setUp() throws Exception {
    super.setUp();

    getActivity().postCensus();
  }

  @LargeTest
  public void testUploadedRequest() {
    assertTrue(getActivity().uploadedRequest);
  }
}
