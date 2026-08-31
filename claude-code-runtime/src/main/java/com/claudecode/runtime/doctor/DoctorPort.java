package com.claudecode.runtime.doctor;

/**
 * Collects a diagnostic snapshot away from the UI thread.
 */
@FunctionalInterface
public interface DoctorPort {

    DoctorReport collect();
}
