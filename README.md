This repository provides source code of all the simulators evaluated in our ASPLOS 2020 paper [*Peacenik: Architecture Support for Not Failing under Fail-Stop Memory Consistency*](http://web.cse.ohio-state.edu/~bond.213/peacenik-asplos-2020.pdf), including the ARC and ARC+Peacenik, CE and CE+Peacenik, and WMM simulators; a Pin-based front end; a framework to drive experiments.

Artifact VM image is available at <https://doi.org/10.5281/zenodo.3603351>. See the artifact appendix in the [Peacenik paper](http://web.cse.ohio-state.edu/~bond.213/peacenik-asplos-2020.pdf) for guidance of using the artifact to run the simulators and reproduce the graphs in the paper.

## Setup the environment on Ubuntu 16.04 LTS

1. Clone code.

```Bash
git clone https://github.com/PLaSSticity/peacenik-simulators-asplos20.git
```

2. Download and install the PARSEC benchmarks and server programs. 

2.1 Our Peacenik evaluation executes benchmarks from the PARSEC suite version 3.0-beta-20150206. The PARSEC suite can be downloaded from http://parsec.cs.princeton.edu/.

The evaluation uses a new build configuration `gcc-pthreads-hooks` to ensure that relevant PARSEC applications use Pthreads as the parallelization model. See the PARSEC section in our [ARC code repository](https://github.com/PLaSSticity/ce-arc-simulator-ipdps19/blob/master/README.md) for more instructions.

2.2 The evaluation runs two server programs, [httpd-2.4.23](https://httpd.apache.org/) and [mysql-5.7.16](https://www.mysql.com/products/community/). You can download the source from [this repository](./server-programs) and use the instructions in their respective README files to build the two programs.

3. Download and install Intel Pin.

Our simulators feed on programs' event traces from a Pin-based frond end. The front end includes our [Pintool](./peacenik-pintool) and its underlying Intel Pin 2.14. The Pintool depends on the Boost library version > 1.58, which is expected to be in the `lib` directory under the Intel Pin root directory (i.e., `intel-pin/lib`). Use the following instructions to install the Pintool:

```Bash
cd; wget https://software.intel.com/sites/landingpage/pintool/downloads/pin-2.14-71313-gcc.4.4.7-linux.tar.gz

tar xvzf pin-2.14-71313-gcc.4.4.7-linux.tar.gz

mv pin-2.14-71313-gcc.4.4.7-linux intel-pin

cd intel-pin/source/tools 

cp -r /path/to/peacenik-pintool ViserST
``` 

4. Install dependencies.

Use the following instrucions to install required packages.

```Bash
apt-get update; apt-get upgrade -y

apt-get install python3-pip python3-dev ant openjdk-9-jre libc6 libstdc++6 lib32stdc++6 -y

sudo pip3 install --upgrade pip numpy scipy psutil django setuptools paramiko jedi cffi
```

5. Setup environment variables.

Copy the following lines to the end of your `.bashrc`.

```
export PIN_ROOT=/path/to/intel-pin                      # Intel Pin
export PINTOOL_ROOT_ST=$PIN_ROOT/source/tools/ViserST     # the Pintool
export PARSEC_ROOT=/path/to/parsec/benchmarks/parsec-3.0  # the PARSEC 3.0 benchmarks
export MESISIM_ROOT=/path/to/ce-peacenik-simulators     # the MESI, CE, CE+Peacenik simulators
export VISERSIM_ROOT=/path/to/arc-peacenik-simulators   # the ARC, ARC+Peacenik simulators
export VISER_EXP=/path/to/peacenik-exp-framework        # the experiment framework

```


6. Create experiment output directories.

```Bash
mkdir exp-output; mkdir exp-products
```

## Run the Peacenik simulation and reproduce the graphs in the Peacenik paper

We provide scripts for you to run the Peacenik simulation and reproduce our results easily. You can follow the [instructions](./exp-scripts) to use the scripts.
