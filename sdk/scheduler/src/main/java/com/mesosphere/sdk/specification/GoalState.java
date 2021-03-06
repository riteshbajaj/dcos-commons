package com.mesosphere.sdk.specification;

/**
 * The allowed goal states for a Task. These are treated as the desired outcome for executing a given task.
 */
public enum GoalState {
  /**
   * ERROR.
   */
  UNKNOWN,

  /**
   * Running continuously. Should be restarted if it exits.
   */
  RUNNING,

  /**
   * Running and then exiting successfully once for a given configuration. Should not be restarted upon a successful
   * exit, but may be run again if there's a config change.
   */
  FINISH,

  /**
   * Running only once over the lifetime of a service. Should not be run again for the duration of this install,
   * regardless of any config changes.
   */
  ONCE
}
