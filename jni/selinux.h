#pragma once

void resolveSELinuxFunctions(void);
int lgetfilecon(const char *path, char** con);
