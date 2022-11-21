resource "aws_instance" "ec2-node" {

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-ec2"
  }

  ami                    = data.aws_ami.ubuntuAMI.id
  instance_type          = "t3.xlarge"
  subnet_id              = aws_subnet.base-stack-private-subnet[0].id
  key_name               = aws_key_pair.ec2-ssh-key.key_name
  vpc_security_group_ids = [aws_security_group.main-sg.id]
  availability_zone      = data.aws_availability_zones.available.names[0]
  iam_instance_profile   = aws_iam_instance_profile.default_profile.name

  root_block_device {
    volume_size = 24
    tags = var.tags
  }

  user_data = templatefile("ubuntu_user_data.sh", {
    awsRegion : data.aws_region.current.name
    tlscacert : file(var.dockerServerCert.tlscacert)
    tlscert : file(var.dockerServerCert.tlscert)
    tlskey : file(var.dockerServerCert.tlskey)
  }
  )


}


resource "aws_key_pair" "ec2-ssh-key" {

  key_name = "${var.tags.useCase}-${var.tags.owner}-key"
  public_key = file(var.ssh-public-key-local-path)
}

//Get the latest Ubuntu 22.04 AMI
data "aws_ami" "ubuntuAMI" {
  most_recent = true

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  owners = ["099720109477"] # Canonical
}

