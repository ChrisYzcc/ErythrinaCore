#include "common.h"
#include <cstddef>
#include <sys/time.h>

static struct timeval boot = {};

uint32_t uptime(void) {
    struct timeval t;
    gettimeofday(&t, NULL);
  
    int s = t.tv_sec - boot.tv_sec;
    int us = t.tv_usec - boot.tv_usec;
    if (us < 0) {
      s--;
      us += 1000000;
    }
  
    return s * 1000 + (us + 500) / 1000;
  }