#include<iostream>
using namespace std;


void pthread_mutex_unlock0(){
    cout << "unlock" << endl;
}
void pthread_mutex_lock0(){
    cout << "lock" << endl;
}

void pthread_create0() {
    cout << "create" << endl;
    pthread_mutex_lock0();
    pthread_mutex_unlock0();
}
int main() {
    cout << "main" << endl;
    pthread_create0();
    return 0;
}
