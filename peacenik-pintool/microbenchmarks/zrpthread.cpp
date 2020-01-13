#include <iostream>
#include <pthread.h>
#include <stdlib.h>
#include <cstring>

#define num_threads 4
int global;
using namespace std;

// takes one parameter, unnamed if you aren't using it
void *print_message(void* threadID) {
  global = 43;
  cout << "global value printed from child thread: " << global << endl;
}

int main(int argc,char *argv[]) {
  if (strstr("abfafda","b")) {
    cout << strstr("abfafadfa","b") << endl;
    return 0;
  }
  //int num_threads = atoi(argv[1]);
  pthread_t threads[num_threads];

  for (int t=0; t < num_threads; t++){
    //cout << "In main: creating thread " << t << endl;
    int rc = pthread_create(&threads[t], NULL, &print_message, (void *)t);
    if (rc) {
      cout << "ERROR; return code from pthread_create() is " << rc << endl;
    }
  }
  global = 42;
  cout << "global value printed from main thread: " << global << endl;
  for (int t=0; t < num_threads; t++)
    pthread_join(threads[t], NULL);
  return 0;
}
