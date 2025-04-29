#ifndef __LIGHTSSS_H__
#define __LIGHTSSS_H__

#include <cstdint>
#include <deque>
#include <list>
#include <signal.h>
#include <sys/ipc.h>
#include <sys/prctl.h>
#include <sys/shm.h>
#include <sys/wait.h>
#include <unistd.h>

typedef struct shinfo {
    bool flag;
    bool notgood;
    uint64_t endCycles;
    pid_t oldest;
  } shinfo;
  
class ForkShareMemory {
private:
    key_t key_n;
    int shm_id;

public:
    shinfo *info;

    ForkShareMemory();
    ~ForkShareMemory();

    void shwait();
};

const int FORK_OK = 0;
const int FORK_ERROR = 1;
const int FORK_CHILD = 2;

class LightSSS {
    pid_t pid = -1;
    int slotCnt = 0;
    int waitProcess = 0;
    // front() is the newest. back() is the oldest.
    std::deque<pid_t> pidSlot = {};
    ForkShareMemory forkshm;

public:
    int do_fork();
    int wakeup_child(uint64_t cycles);
    bool is_child();
    int do_clear();
    uint64_t get_end_cycles() {
        return forkshm.info->endCycles;
    }
};

#define FORK_PRINTF(format, args...)                       \
do {                                                     \
    Info("[FORK_INFO pid(%d)] " format, getpid(), ##args); \
    fflush(stdout);                                        \
} while (0);
  
#endif