# Makefile for building the sieve_postal JNI shared library.
#
# Prerequisites:
#   - libpostal installed (headers in /usr/local/include, libs in /usr/local/lib)
#   - JAVA_HOME set or auto-detected
#
# Usage:
#   make              # build libsieve_postal.so (Linux) or libsieve_postal.dylib (macOS)
#   make clean        # remove built artifacts

JAVA_HOME ?= $(shell java -XshowSettings:properties -version 2>&1 \
               | grep 'java.home' | sed 's/.*= //')

UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
    JNI_OS_DIR  := darwin
    LIB_NAME    := libsieve_postal.dylib
    SHARED_FLAG := -dynamiclib
else
    JNI_OS_DIR  := linux
    LIB_NAME    := libsieve_postal.so
    SHARED_FLAG := -shared
endif

CC      ?= gcc
CFLAGS  := -fPIC -O2 -Wall -Wextra
INCLUDES := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/$(JNI_OS_DIR)
LDFLAGS  := -lpostal

.PHONY: all clean

all: $(LIB_NAME)

$(LIB_NAME): dev_sieve_address_LibPostal.c
	$(CC) $(SHARED_FLAG) $(CFLAGS) $(INCLUDES) -o $@ $< $(LDFLAGS)

clean:
	rm -f libsieve_postal.so libsieve_postal.dylib
