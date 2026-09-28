package com.rimo.example;

//? if fabric {
import com.rimo.example.loaders.fabric.Platform;
//? } else if neoforge {
/*import com.rimo.example.loaders.neoforge.Platform;
*///? } else if forge {
/*import com.rimo.example.loaders.forge.Platform;
*///? }

//? if = 1.16.5 {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///? } else {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? }

public class Common {
	public static final String MOD_ID = "example";
	//~if = 1.16.5 'LoggerFactory.' -> 'LogManager.'
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static void init() {
		// example for platform specific function call...
		boolean isSFCRLoaded = Platform.isModLoaded("sfcr");
	}
}
