/*
  RCDC-sim: A Relaxed Consistency Deterministic Computer simulator
  Copyright 2011 University of Washington

  Contributed by Joseph Devietti

This file is part of RCDC-sim.

RCDC-sim is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

RCDC-sim is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with RCDC-sim.  If not, see <http://www.gnu.org/licenses/>.
*/

#include <assert.h>
#include <fstream>
#include <iostream>
#include <vector>

using namespace std;

int main(int argc, char **argv) {

    if (argc < 3) {
        cout << "[pipefork] Usage: " << argv[0] << " SOURCE DESTINATIONS..." << endl;
        return 1;
    }

    const char *sourcePipe = argv[1];

    ifstream sourceFifo;
    sourceFifo.open(sourcePipe, ios::in | ios::binary);
    assert(sourceFifo.good());

    vector<ofstream *> destFifos;
    for (int i = 2; i < argc; i++) {
        ofstream *dest = new ofstream;
        dest->open(argv[i], ios::out | ios::binary);
        destFifos.push_back(dest);
    }

    char buf[29 + 14]; // Should increase this for sending site tracking info under the lockstep execution
    vector<ofstream *>::iterator os;

    while (true) {
        // SB: http://www.cplusplus.com/reference/ios/ios/good/
        if (!sourceFifo.eof()) {
            assert(sourceFifo.good());
        }

        // read input
        sourceFifo.read(&buf[0], sizeof(buf));
        unsigned bytesRead = sourceFifo.gcount();
        if (0 == bytesRead) { // no more events
            assert(sourceFifo.eof());
            goto CLEANUP;
        }

        // multicast input to all destinations
        for (os = destFifos.begin(); os != destFifos.end(); os++) {
            assert((*os)->good());
            (*os)->write(&buf[0], bytesRead);
            // SB: This is important to ensure event communication over pipes on a per-event basis and not in
            // blocks of certain sizes
            (*os)->flush();
        }

    } // end while(true)

CLEANUP: //
    sourceFifo.close();
    for (os = destFifos.begin(); os != destFifos.end(); os++) {
        (*os)->flush();
        (*os)->close();
    }

    cout << "[pipefork] Exiting" << endl;
    return 0;
}
