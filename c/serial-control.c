/*

Simple serial interface:

- opens selected serial port
- sends command line parameters to serial port
- returns responses
- closes port

*/


#include<stdio.h>
#include<string.h>
#include<unistd.h>
#include<fcntl.h>
#include<errno.h>
#include<termios.h>

int open_port(char *device)
{
  int fd; 
  struct termios options;

  fd = open(device, O_RDWR | O_NOCTTY | O_NDELAY); 

  if (fd<0)
  {
    return -1;
  }

  fcntl(fd, F_SETFL, 0);
  tcgetattr(fd, &options);
  cfsetispeed(&options, B9600);
  cfsetospeed(&options, B9600);
  options.c_cflag |= (CLOCAL | CREAD);
  tcsetattr(fd, TCSANOW, &options);

  tcflush(fd,TCIOFLUSH);

  return (fd);
}

void read_response(int fd)
{
  int i;

   // read response(s)
  // Initialize file descriptor sets
  fd_set read_fds, write_fds, except_fds;
  FD_ZERO(&read_fds);
  FD_ZERO(&write_fds);
  FD_ZERO(&except_fds);
  FD_SET(fd, &read_fds);

  // Set timeout to 1.0 seconds
  struct timeval timeout;
  timeout.tv_sec = 1;
  timeout.tv_usec = 0;

   char buffer[1];

   while(select(fd + 1, &read_fds, &write_fds, &except_fds, &timeout) == 1)
   {
      i = read(fd, &buffer, 1);
      printf ("%c", buffer[0]);   
   }
}
 
 
int main(int argc, char* argv[])
{
  int fd; 

  if(argc<3)
  {
    printf("Usage: serial-control <device> <data1> ... <dataN> \n");
    return 0;
  }

  if(strlen(argv[2])>10)
  {
    printf("Data too long\n");
    return 0;    
  }

  // retry a few times - Bluetooth port can be slow to respond
  int n=3;

  do
  {
   fd = open_port(argv[1]);


   n--;

    if(fd<=0 && n>0)
     {
        printf("Unable to open device %s retrying...\n", argv[1]);
        sleep(1);
     }


 }while(fd<=0 && n>0);

  if(fd<=0)
   {
      printf("Unable to open device %s\n", argv[1]);
      return 0;
   }
 
    char lf='\n';
    int i;

    for(i=2;i<argc;i++)
    {
      write(fd, argv[i], strlen(argv[i]));
      write(fd, &lf, sizeof(lf));
      printf("Sent %s to %s\n", argv[i], argv[1]);
   }

   read_response(fd);

   close(fd);

   return 1;
}



