#include <iostream>
#include <pthread.h>
#include <cstdlib>

#define NUM_THREADS 2

using namespace std;

pthread_rwlock_t rwlock = PTHREAD_RWLOCK_INITIALIZER;

/* create thread argument struct for thr_func() */
typedef struct _thread_data_t {
  int tid;
} thread_data_t;

// Should not access parameters that are on the stack in main()
// http://stackoverflow.com/questions/6524433/passing-multiple-arguments-to-a-thread-in-c-pthread-create

void reader(thread_data_t* data) {
  //pthread_rwlock_rdlock(&rwlock);
  cout << "Reader lock is acquired by thread:" << data->tid << endl;
  //pthread_rwlock_unlock(&rwlock);
}

void writer(thread_data_t* data) {
  //pthread_rwlock_wrlock(&rwlock);
  cout << "Writer lock is acquired by thread:" << data->tid << endl;
  //pthread_rwlock_unlock(&rwlock);
}

// takes one parameter, unnamed if you aren't using it
void *print_message(void* threadData) {
  thread_data_t *data = (thread_data_t *) threadData;
  cout << "Hello from child thread:" << data->tid << endl;
  if (data->tid % 2 == 0) { // even threads are readers
    reader(data);
  } else {
    writer(data);
  }
  pthread_exit(NULL);
}

int main(int argc, char* argv[]) {
  pthread_t threads[NUM_THREADS];
  /* create a thread_data_t argument array */
  thread_data_t thr_data[NUM_THREADS];

  cout << "Hello from main thread" << endl;

  for (int t = 0; t < NUM_THREADS; t++){
    cout << "In main: creating thread " << t << endl;
    thr_data[t].tid = t;
    int rc = pthread_create(&threads[t], NULL, &print_message, &thr_data[t]);
    if (rc) {
      cerr << "ERROR: return code from pthread_create() is " << rc << endl;
      return EXIT_FAILURE;
    }
  }

  /* block until all threads complete */
  /*  for (int i = 0; i < NUM_THREADS; ++i) {
    pthread_join(threads[i], NULL);
    }*/

  //pthread_exit(NULL);
  return EXIT_SUCCESS;
}
