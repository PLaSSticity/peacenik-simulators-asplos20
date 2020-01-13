#include <iostream>
#include <pthread.h>

#define NUM_THREADS 1

using namespace std;

// takes one parameter, unnamed if you aren't using it
void *print_message(void* threadID) {
cout << "Hello from child thread" << endl;
}

int main() {
  pthread_t threads[NUM_THREADS];

for (int t=0; t < NUM_THREADS; t++){
cout << "In main: creating thread " << t << endl;
int rc = pthread_create(&threads[t], NULL, &print_message, (void *)t);
if (rc) {
cout << "ERROR; return code from pthread_create() is " << rc << endl;
}
}

cout << "Hello from main thread" << endl;
pthread_exit(NULL);

return 0;
}
