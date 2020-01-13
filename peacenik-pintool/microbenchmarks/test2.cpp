#include <list>
#include <iostream>
#include <iomanip>

using namespace std;

int main() {
  int a = 40, b = 10;
  int* c = new int(999);
  cout << a << " " << &a <<endl;
  cout << b << " " << &b <<endl;
  cout << *c << " " << c << " " << &c <<endl;
  return 0;
}
