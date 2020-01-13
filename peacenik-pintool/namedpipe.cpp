#include <cassert>
#include <cstdlib>
#include <errno.h>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "boost/lexical_cast.hpp"

using namespace std;

string fifoNamePrefix = string(getenv("ST_PINTOOL_ROOT")) + "/fifo.tid";

void handleMkfifoError(string fifoName, int ret, uint32_t numFifos) {
    cerr << "[namedpipe] mkfifo has failed for fifo:" << fifoName
         << " Error: " << errno << " ";
    switch (errno) {
    case EACCES: {
        cout << "EACCESS";
        break;
    }
    case EDQUOT: {
        cout << "EDQUOT";
        break;
    }
    case EEXIST: {
        cout << "EEXIST";
        break;
    }
    case ENAMETOOLONG: {
        cout << "ENAMETOOLONG";
        break;
    }
    case ENOENT: {
        cout << "ENOENT";
        break;
    }
    case ENOSPC: {
        cout << "ENOSPC";
        break;
    }
    case ENOTDIR: {
        cout << "ENOTDIR";
        break;
    }
    case EROFS: {
        cout << "EROFS";
        break;
    }
    default: {
        cout << "Unknown error type";
    }
    }
    cout << endl;

    for (uint32_t i = 0; i < numFifos; i++) {
        string fifoName = fifoNamePrefix + boost::lexical_cast<string>(i);
        // cout << "[namedpipe] Closing fifo: " << fifoName << endl;
        unlink(fifoName.c_str());
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        cerr << "[namedpipe] Usage: " << argv[0] << " NUMFIFOs BENCHMARK" << endl;
        return 1;
    }
    uint32_t numFifos = boost::lexical_cast<uint32_t>(argv[1]);

    for (uint32_t i = 0; i < numFifos; i++) {
        string fifoName = fifoNamePrefix + boost::lexical_cast<string>(i) + string(argv[2]);
        // cout << "[namedpipe] Before opening the fifo " << fifoName << " for reading" << endl;
        int ret = mkfifo(fifoName.c_str(), 0666); // Create the FIFO
        if (ret == -1) {
            handleMkfifoError(fifoName, ret, numFifos);
            exit(EXIT_FAILURE);
        }
        // cout << "[namedpipe] Opened the fifo " << fifoName << " for reading" << endl;
    }
    return 0;
}
