/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2010 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 * 
 * WARNING: This is generated code. Modify at your own risk and without support.
 */

#import "TiBlob.h"
#import "Mimetypes.h"
#import "TiUtils.h"
#import "UIImage+Alpha.h"
#import "UIImage+Resize.h"
#import "UIImage+RoundedCorner.h"

@implementation TiBlob

-(void)dealloc
{
	RELEASE_TO_NIL(mimetype);
	RELEASE_TO_NIL(data);
	RELEASE_TO_NIL(image);
	RELEASE_TO_NIL(path);
	[super dealloc];
}

-(id)description
{
	return @"[object TiBlob]";
}

-(BOOL)isImageMimeType
{
	return [mimetype hasPrefix:@"image/"];
}

-(void)ensureImageLoaded
{
	if (image == nil && [self isImageMimeType])
	{
		image = [[self image] retain];
	}
}

-(NSInteger)width
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		return image.size.width;
	}
	return 0;
}

-(NSInteger)height
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		return image.size.height;
	}
	return 0;
}

-(NSInteger)size
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		return image.size.width * image.size.height;
	}
	switch (type)
	{
		case TiBlobTypeData:
		{
			return [data length];
		}
		case TiBlobTypeFile:
		{
			NSFileManager *fm = [NSFileManager defaultManager];
			NSError *error = nil; 
			NSDictionary * resultDict = [fm attributesOfItemAtPath:path error:&error];
			id result = [resultDict objectForKey:NSFileSize];
			if (error!=NULL)
			{
				return 0;
			}
			return [result intValue];
		}
	}
	return 0;
}

-(id)initWithImage:(UIImage*)image_
{
	if (self = [super init])
	{
		image = [image_ retain];
		type = TiBlobTypeImage;
		mimetype = [@"image/jpeg" retain];
	}
	return self;
}

-(id)initWithData:(NSData*)data_ mimetype:(NSString*)mimetype_
{
	if (self = [super init])
	{
		data = [data_ retain];
		type = TiBlobTypeData;
		mimetype = [mimetype_ retain];
	}
	return self;
}

-(id)initWithFile:(NSString*)path_
{
	if (self = [super init])
	{
		type = TiBlobTypeFile;
		path = [path_ retain];
		mimetype = [Mimetypes mimeTypeForExtension:path];
	}
	return self;
}

-(TiBlobType)type
{
	return type;
}

-(NSString*)mimeType
{
	return mimetype;
}

-(NSString*)text
{
	switch (type)
	{
		case TiBlobTypeFile:
		{
			NSData *fdata = [self data];
			return [[[NSString alloc] initWithData:fdata encoding:NSUTF8StringEncoding] autorelease];
		}
		case TiBlobTypeData:
		{
			return [[[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] autorelease];
		}
	}
	// anything else we refuse to write out
	return nil;
}

-(NSData*)data
{
	switch(type)
	{
		case TiBlobTypeFile:
		{
			NSError *error = nil;
			return [NSData dataWithContentsOfFile:path options:0 error:&error];
		}
		case TiBlobTypeImage:
		{
			return UIImageJPEGRepresentation(image,1.0);
		}
	}
	return data;
}

-(UIImage*)image
{
	switch(type)
	{
		case TiBlobTypeFile:
		{
			return [UIImage imageWithContentsOfFile:path];
		}
		case TiBlobTypeData:
		{
			return [UIImage imageWithData:data];
		}
	}
	return image;
}

-(void)setData:(NSData*)data_
{
	RELEASE_TO_NIL(data);
	type = TiBlobTypeData;
	data = [data_ retain];
}

-(void)setImage:(UIImage *)image_
{
	RELEASE_TO_NIL(image);
	type = TiBlobTypeImage;
	image = [image_ retain];
}

-(NSString*)path
{
	return path;
}

-(void)setMimeType:(NSString*)mime type:(TiBlobType)type_
{
	RELEASE_TO_NIL(mimetype);
	mimetype = [mime retain];
	type = type_;
}

-(BOOL)writeTo:(NSString*)destination error:(NSError**)error
{
	NSData *writeData = nil;
	switch(type)
	{
		case TiBlobTypeFile:
		{
			NSFileManager *fm = [NSFileManager defaultManager];
			return [fm copyItemAtPath:path toPath:destination error:error];
		}
		case TiBlobTypeImage:
		{
			writeData = [self data];
			break;
		}
		case TiBlobTypeData:
		{
			writeData = data;
			break;
		}
	}
	if (writeData!=nil)
	{
		[writeData writeToFile:destination atomically:YES];
	}
	return NO;
}

#pragma mark Image Manipulations

- (id)imageWithAlpha:(id)args
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		return [[[TiBlob alloc] initWithImage:[UIImageAlpha imageWithAlpha:image]] autorelease];
	}
	return nil;
}

- (id)imageWithTransparentBorder:(id)args
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		ENSURE_SINGLE_ARG(args,NSObject);
		NSUInteger size = [TiUtils intValue:args];
		return [[[TiBlob alloc] initWithImage:[UIImageAlpha transparentBorderImage:size image:image]] autorelease];
	}
	return nil;
}

- (id)imageWithRoundedCorner:(id)args
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		NSUInteger cornerSize = [TiUtils intValue:[args objectAtIndex:0]];
		NSUInteger borderSize = [args count] > 1 ? [TiUtils intValue:[args objectAtIndex:1]] : 1;
		return [[[TiBlob alloc] initWithImage:[UIImageRoundedCorner roundedCornerImage:cornerSize borderSize:borderSize image:image]] autorelease];
	}
	return nil;
}

- (id)imageAsThumbnail:(id)args
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		NSUInteger size = [TiUtils intValue:[args objectAtIndex:0]];
		NSUInteger borderSize = [args count] > 1 ? [TiUtils intValue:[args objectAtIndex:1]] : 1;
		NSUInteger cornerRadius = [args count] > 2 ? [TiUtils intValue:[args objectAtIndex:2]] : 0;
		return [[[TiBlob alloc] initWithImage:[UIImageResize thumbnailImage:size 
												  transparentBorder:borderSize
													   cornerRadius:cornerRadius
											   interpolationQuality:kCGInterpolationHigh
															  image:image]] 
				autorelease];
	}
	return nil;
}

- (id)imageAsResized:(id)args
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		ENSURE_ARG_COUNT(args,2);
		NSUInteger width = [TiUtils intValue:[args objectAtIndex:0]];
		NSUInteger height = [TiUtils intValue:[args objectAtIndex:1]];
		return [[[TiBlob alloc] initWithImage:[UIImageResize resizedImage:CGSizeMake(width, height) interpolationQuality:kCGInterpolationHigh image:image]] autorelease];
	}
	return nil;
}

- (id)imageAsCropped:(id)args
{
	[self ensureImageLoaded];
	if (image!=nil)
	{
		ENSURE_ARG_COUNT(args,4);
		NSUInteger x = [TiUtils intValue:[args objectAtIndex:0]];
		NSUInteger y = [TiUtils intValue:[args objectAtIndex:1]];
		NSUInteger width = [TiUtils intValue:[args objectAtIndex:2]];
		NSUInteger height = [TiUtils intValue:[args objectAtIndex:3]];

		return [[[TiBlob alloc] initWithImage:[UIImageResize croppedImage:CGRectMake(x, y, width, height) image:image]] autorelease];
	}
	return nil;
}

-(id)toString:(id)args
{
	id t = [self text];
	if (t!=nil)
	{
		return t;
	}
	return [super toString:args];
}

@end
